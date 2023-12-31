/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.room.model.relation.RelationDefaultContent

/**
 * Class representing the org.matrix.msc3381.poll.end event content.
 */
@JsonClass(generateAdapter = true)
data class MessageEndPollContent(
        /**
         * Local message type, not from server.
         */
        @Transient
        override val msgType: String = MessageType.MSGTYPE_POLL_END,
        @Json(name = "body") override val body: String = "",
        @Json(name = "m.new_content") override val newContent: Content? = null,
        @Json(name = "m.relates_to") override val relatesTo: RelationDefaultContent? = null,
        @Json(name = "org.matrix.msc1767.text") val unstableText: String? = null,
        @Json(name = "m.text") val text: String? = null,
) : MessageContent {
    fun getBestText() = text ?: unstableText
}
