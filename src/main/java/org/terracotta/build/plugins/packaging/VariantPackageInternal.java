/*
 * Copyright Terracotta, Inc.
 * Copyright IBM Corp. 2024, 2025
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terracotta.build.plugins.packaging;

import static java.text.MessageFormat.format;
import static org.terracotta.build.PluginUtils.capitalize;

public abstract class VariantPackageInternal extends PackageInternal implements VariantPackage, CustomCapabilitiesInternal {

  @Override
  protected String camelName(String base) {
    return getName() + capitalize(base);
  }

  @Override
  protected String kebabName(String base) {
    if (base.isEmpty()) {
      return getName();
    } else {
      return getName() + "-" + base;
    }
  }

  @Override
  protected String description(String template) {
    return format(template, "the " + getName());
  }
}
