/*
 * Copyright (C) 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.authoring;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.copybara.doc.annotations.DocField;
import com.google.devtools.build.lib.events.Location;
import com.google.devtools.build.lib.skylarkinterface.Param;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModule;
import com.google.devtools.build.lib.skylarkinterface.SkylarkModuleCategory;
import com.google.devtools.build.lib.skylarkinterface.SkylarkSignature;
import com.google.devtools.build.lib.syntax.BuiltinFunction;
import com.google.devtools.build.lib.syntax.EvalException;
import com.google.devtools.build.lib.syntax.SkylarkList;
import com.google.devtools.build.lib.syntax.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Represents the authors mapping between an origin and a destination.
 *
 * <p>For a given author in the origin, always provides an author in the destination.
 */
@SkylarkModule(
    name = "authoring_class",
    namespace = true,
    doc = "The authors mapping between an origin and a destination",
    category = SkylarkModuleCategory.BUILTIN)
public final class Authoring {

  private final Author defaultAuthor;
  private final AuthoringMappingMode mode;
  private final ImmutableSet<String> whitelist;

  @VisibleForTesting
  public Authoring(
      Author defaultAuthor, AuthoringMappingMode mode, ImmutableSet<String> whitelist) {
    this.defaultAuthor = Preconditions.checkNotNull(defaultAuthor);
    this.mode = Preconditions.checkNotNull(mode);
    this.whitelist = Preconditions.checkNotNull(whitelist);
  }

  /**
   * Returns the mapping mode.
   */
  public AuthoringMappingMode getMode() {
    return mode;
  }

  /**
   * Returns the default author, used for squash workflows,
   * {@link AuthoringMappingMode#USE_DEFAULT} mode and for non-whitelisted authors.
   */
  public Author getDefaultAuthor() {
    return defaultAuthor;
  }

  /**
   * Returns a {@code Set} of whitelisted author identifiers.
   *
   * <p>An identifier is typically an email but might have different representations depending on
   * the origin.
   */
  public ImmutableSet<String> getWhitelist() {
    return whitelist;
  }

  /**
   * Returns true if the user can be safely used.
   */
  public boolean useAuthor(String userId) {
    switch (mode) {
      case PASS_THRU:
        return true;
      case USE_DEFAULT:
        return false;
      case WHITELIST:
        return whitelist.contains(userId);
      default:
        throw new IllegalStateException(String.format("Mode '%s' not implemented.", mode));
    }
  }

  @SkylarkModule(
      name = "authoring",
      namespace = true,
      doc = "The authors mapping between an origin and a destination",
      category = SkylarkModuleCategory.BUILTIN)
  public static final class Module {

    @SkylarkSignature(name = "overwrite", returnType = Authoring.class,
        doc = "Use the default author for all the submits in the destination.",
        parameters = {
            @Param(name = "default", type = String.class,
                doc = "The default author for commits in the destination"),
        },
        objectType = Module.class, useLocation = true)
    public static final BuiltinFunction OVERWRITE = new BuiltinFunction("overwrite") {
      public Authoring invoke(String defaultAuthor, Location location)
          throws EvalException {
        return new Authoring(Author.parse(location, defaultAuthor),
            AuthoringMappingMode.USE_DEFAULT,
            ImmutableSet.<String>of());
      }
    };

    @SkylarkSignature(name = "new_author", returnType = Author.class,
        doc = "Create a new author from a string with the form 'name <foo@bar.com>'",
        parameters = {
            @Param(name = "author_string", type = String.class,
                doc = "A string representation of the author with the form 'name <foo@bar.com>'"),
        }, useLocation = true)
    public static final BuiltinFunction NEW_AUTHOR = new BuiltinFunction("new_author") {
      public Author invoke(String authorString, Location location)
          throws EvalException {
        return Author.parse(location, authorString);
      }
    };

    @SkylarkSignature(name = "pass_thru", returnType = Authoring.class,
        doc = "Use the origin author as the author in the destination, no whitelisting.",
        parameters = {
            @Param(name = "default", type = String.class,
                doc = "The default author for commits in the destination. This is used"
                    + " in squash mode workflows"),
        },
        objectType = Module.class, useLocation = true)
    public static final BuiltinFunction PASS_THRU = new BuiltinFunction("pass_thru") {
      public Authoring invoke(String defaultAuthor, Location location)
          throws EvalException {
        return new Authoring(Author.parse(location, defaultAuthor),
            AuthoringMappingMode.PASS_THRU,
            ImmutableSet.<String>of());
      }
    };

    @SkylarkSignature(name = "whitelisted", returnType = Authoring.class,
        doc = "Create an individual or team that contributes code.",
        parameters = {
            @Param(name = "default", type = String.class,
                doc = "The default author for commits in the destination. This is used"
                    + " in squash mode workflows or when users are not whitelisted."),
            @Param(name = "whitelist", type = SkylarkList.class,
                generic1 = String.class,
                doc = "List of white listed authors in the origin. The authors must be unique"),
        },
        objectType = Module.class, useLocation = true)
    public static final BuiltinFunction WHITELISTED = new BuiltinFunction("whitelisted") {
      public Authoring invoke(String defaultAuthor, SkylarkList<String> whitelist,
          Location location)
          throws EvalException {
        return new Authoring(Author.parse(location, defaultAuthor),
            AuthoringMappingMode.WHITELIST,
            createWhitelist(location, Type.STRING_LIST.convert(whitelist, "whitelist")));
      }
    };

    private static ImmutableSet<String> createWhitelist(Location location, List<String> whitelist)
        throws EvalException {
      if (whitelist.isEmpty()) {
        throw new EvalException(location, "'whitelisted' function requires a non-empty 'whitelist'"
            + " field. For default mapping, use 'overwrite(...)' mode instead.");
      }
      Set<String> uniqueAuthors = new HashSet<>();
      for (String author : whitelist) {
        if (!uniqueAuthors.add(author)) {
          throw new EvalException(location,
              String.format("Duplicated whitelist entry '%s'", author));
        }
      }
      return ImmutableSet.copyOf(whitelist);
    }
  }

  /**
   * Mode used for author mapping from origin to destination.
   */
  @SkylarkModule(
      name = "authoring_mode",
      doc = "Mode used for author mapping from origin to destination",
      category = SkylarkModuleCategory.TOP_LEVEL_TYPE)
  public enum AuthoringMappingMode {
    /**
     * Use the default author for all the submits in the destination.
     */
    @DocField(description = "Use the default author for all the submits in the destination.")
    USE_DEFAULT,
    /**
     * Use the origin author as the author in the destination, no whitelisting.
     */
    @DocField(description =
        "Use the origin author as the author in the destination, no whitelisting.")
    PASS_THRU,
    /**
     * Use the whitelist map to translate origin authors to destination. Use the default author for
     * non-whitelisted authors.
     */
    @DocField(description = "Use the whitelist map to translate origin authors to destination. "
        + "Use the default author for non-whitelisted authors.")
    WHITELIST
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Authoring authoring = (Authoring) o;
    return Objects.equals(defaultAuthor, authoring.defaultAuthor) &&
        mode == authoring.mode &&
        Objects.equals(whitelist, authoring.whitelist);
  }

  @Override
  public int hashCode() {
    return Objects.hash(defaultAuthor, mode, whitelist);
  }

  @Override
  public String toString() {
    return MoreObjects.toStringHelper(this)
        .add("defaultAuthor", defaultAuthor)
        .add("mode", mode)
        .add("whitelist", whitelist)
        .toString();
  }
}
