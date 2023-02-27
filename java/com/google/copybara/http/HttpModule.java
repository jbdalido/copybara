/*
 * Copyright (C) 2023 Google Inc.
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

package com.google.copybara.http;

import com.google.copybara.EndpointProvider;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.util.console.Console;
import net.starlark.java.annot.Param;
import net.starlark.java.annot.StarlarkBuiltin;
import net.starlark.java.annot.StarlarkMethod;
import net.starlark.java.eval.StarlarkValue;

/** Starlark methods for working with the http endpoint. */
@StarlarkBuiltin(name = "http", doc = "module for working with http endpoints")
public class HttpModule implements StarlarkValue {
  HttpOptions options;
  Console console;

  public HttpModule(Console console, HttpOptions options) {
    this.console = console;
    this.options = options;
  }

  @StarlarkMethod(
      name = "endpoint",
      doc = "executes http requests",
      parameters = {@Param(name = "host", named = true)})
  public EndpointProvider<HttpEndpoint> endpoint(String host) throws ValidationException {
    return EndpointProvider.wrap(new HttpEndpoint(console, options.getTransport(), host));
  }
}
