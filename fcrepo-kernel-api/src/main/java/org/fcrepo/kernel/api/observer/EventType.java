/*
 * Copyright 2015 DuraSpace, Inc.
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
package org.fcrepo.kernel.api.observer;

/**
 * A convenient abstraction over JCR's integer-typed events.
 *
 * @author ajs6f
 * @since Feb 7, 2013
 */
public enum EventType {
    NODE_ADDED("node added"),
    NODE_REMOVED("node removed"),
    PROPERTY_ADDED("property added"),
    PROPERTY_REMOVED("property removed"),
    PROPERTY_CHANGED("property changed"),
    NODE_MOVED("node moved"),
    PERSIST("persist");

    private final String eventName;

    EventType(final String eventName) {
        this.eventName = eventName;
    }

    /**
     * @return a human-readable name for this event
     */
    public String getName() {
        return this.eventName;
    }
}
