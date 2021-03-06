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

package com.google.copybara.folder;

import com.google.common.base.Preconditions;
import com.google.copybara.Reference;
import com.google.copybara.RepoException;
import java.nio.file.Path;
import java.time.Instant;
import javax.annotation.Nullable;

/**
 * A reference for folder origins.
 */
public class FolderReference implements Reference {

  final Path path;
  private final Instant timestamp;
  private final String labelName;

  FolderReference(Path path, Instant timestamp, String labelName) {
    this.path = Preconditions.checkNotNull(path);
    this.timestamp = timestamp;
    this.labelName = Preconditions.checkNotNull(labelName);
  }

  @Override
  public String asString() {
    return path.toString();
  }

  @Nullable
  @Override
  public Instant readTimestamp() throws RepoException {
    return timestamp;
  }

  @Override
  public String getLabelName() {
    return labelName;
  }
}
