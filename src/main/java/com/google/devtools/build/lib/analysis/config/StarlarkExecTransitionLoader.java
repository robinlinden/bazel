// Copyright 2023 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.analysis.config;

import com.google.auto.value.AutoValue;
import com.google.common.base.Splitter;
import com.google.devtools.build.lib.analysis.starlark.StarlarkAttributeTransitionProvider;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.cmdline.LabelSyntaxException;
import com.google.devtools.build.lib.skyframe.BzlLoadFailedException;
import com.google.devtools.build.lib.skyframe.BzlLoadValue;
import com.google.devtools.build.lib.skyframe.StarlarkBuiltinsValue;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Utility class for loading a Starlark exec transition from source and making it available as an
 * {@link StarlarkAttributeTransitionProvider}.
 */
public final class StarlarkExecTransitionLoader {

  /** Thrown when the Starlark transition failed to load. */
  public static class StarlarkExecTransitionLoadingException extends Exception {
    public StarlarkExecTransitionLoadingException(String context, String ref, String message) {
      super(
          String.format(
              "Bad Starlark transition reference from %s: %s. %s.", context, ref, message));
    }
  }

  /** Caller-provided logic for Skyframe-evaluating {@link BzlLoadValue.Key}s. */
  public interface BzlFileLoader {
    /**
     * Loads the given {@link BzlLoadValue.Key}. Returns null if not all Skyframe deps are ready.
     */
    @Nullable
    BzlLoadValue getValue(BzlLoadValue.Key key) throws BzlLoadFailedException, InterruptedException;
  }

  /**
   * Loads the Starlark transition that implements execution transition logic according to {@link
   * CoreOptions#starlarkExecConfig}.
   *
   * @param options the current configured target's {@link BuildOptions}. This is used to find the
   *     value for {@link CoreOptions#starlarkExecConfig}.
   * @param bzlFileLoader caller-provided logic for loading {@link BzlLoadValue.Key} skyvalues.
   * @return null if Skyframe deps need loading. A filled {@link Optional} if this build implements
   *     the exec transition with a Starlark transition. An empty {@link Optional} if this build
   *     implements the exec transition with native logic.
   * @throws StarlarkExecTransitionLoadingException if the desired transition isn't a valid Starlark
   *     exec transition.
   */
  @Nullable
  public static Optional<StarlarkAttributeTransitionProvider> loadStarlarkExecTransition(
      @Nullable BuildOptions options, BzlFileLoader bzlFileLoader)
      throws StarlarkExecTransitionLoadingException, InterruptedException {
    if (options == null) {
      return Optional.empty();
    }
    String userRef = options.get(CoreOptions.class).starlarkExecConfig;
    if (userRef == null) {
      return Optional.empty(); // Use the native exec transition.
    }
    TransitionReference parsedRef =
        TransitionReference.create(userRef, "--experimental_exec_config");
    BzlLoadValue bzlValue;
    try {
      bzlValue =
          bzlFileLoader.getValue(
              Objects.equals(
                      parsedRef.bzlFile().getRepository(), StarlarkBuiltinsValue.BUILTINS_REPO)
                  ? BzlLoadValue.keyForBuiltins(parsedRef.bzlFile())
                  : BzlLoadValue.keyForBuild(parsedRef.bzlFile()));
    } catch (BzlLoadFailedException e) {
      throw new StarlarkExecTransitionLoadingException(
          "--experimental_exec_config", userRef, e.getMessage());
    }
    if (bzlValue == null) {
      return null;
    }
    Object transition = bzlValue.getModule().getGlobal(parsedRef.starlarkSymbolName());
    if (transition == null) {
      throw new StarlarkExecTransitionLoadingException(
          "--experimental_exec_config",
          userRef,
          String.format("%s not found in %s", parsedRef.starlarkSymbolName(), parsedRef.bzlFile()));
    } else if (!(transition instanceof StarlarkDefinedConfigTransition)) {
      throw new StarlarkExecTransitionLoadingException(
          "--experimental_exec_config",
          userRef,
          parsedRef.starlarkSymbolName() + " is not a Starlark transition");
    }
    return Optional.of(
        new StarlarkAttributeTransitionProvider((StarlarkDefinedConfigTransition) transition));
  }

  /**
   * Structured form of a Starlark transition reference.
   *
   * <p>In other words, structured form of <code>//pkg:def.bzl%transition_name</code>
   */
  @AutoValue
  abstract static class TransitionReference {

    /** The .bzl file where this transition is defined. */
    abstract Label bzlFile();

    /** The transition's Starlark symbol name. */
    abstract String starlarkSymbolName();

    /**
     * Returns a structured form of a user-specified Starlark transition reference.
     *
     * @throws StarlarkExecTransitionLoadingException on parsing errors.
     */
    static TransitionReference create(String userRef, String context)
        throws StarlarkExecTransitionLoadingException {
      List<String> splitval = Splitter.on('%').splitToList(userRef);
      if (splitval.size() < 2 || splitval.get(1).isEmpty()) {
        throw new StarlarkExecTransitionLoadingException(
            context, userRef, "Doesn't match expected form //pkg:file.bzl%%symbol");
      }
      try {
        return new AutoValue_StarlarkExecTransitionLoader_TransitionReference(
            Label.parseCanonical(splitval.get(0)), splitval.get(1));
      } catch (LabelSyntaxException e) {
        throw new StarlarkExecTransitionLoadingException(
            context, userRef, String.format("Bad label %s: %s", splitval.get(0), e.getMessage()));
      }
    }
  }

  private StarlarkExecTransitionLoader() {}
}
