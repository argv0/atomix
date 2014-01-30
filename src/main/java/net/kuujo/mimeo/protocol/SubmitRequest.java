/*
 * Copyright 2014 the original author or authors.
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
package net.kuujo.mimeo.protocol;

import net.kuujo.mimeo.Command;
import net.kuujo.mimeo.serializer.Serializer;

import org.vertx.java.core.eventbus.Message;
import org.vertx.java.core.json.JsonObject;

/**
 * A submit request.
 * 
 * @author Jordan Halterman
 */
public class SubmitRequest extends Request {
  private static final Serializer serializer = Serializer.getInstance();
  private Command command;

  public SubmitRequest() {
  }

  public SubmitRequest(Command command) {
    this.command = command;
  }

  public static SubmitRequest fromJson(JsonObject json) {
    return serializer.deserialize(json, SubmitRequest.class);
  }

  public static SubmitRequest fromJson(JsonObject json, Message<JsonObject> message) {
    return serializer.deserialize(json, SubmitRequest.class).setMessage(message);
  }

  public static JsonObject toJson(SubmitRequest request) {
    return serializer.serialize(request);
  }

  private SubmitRequest setMessage(Message<JsonObject> message) {
    this.message = message;
    return this;
  }

  public Command command() {
    return command;
  }

  public void reply(JsonObject result) {
    super.reply(new JsonObject().putObject("result", result));
  }

}