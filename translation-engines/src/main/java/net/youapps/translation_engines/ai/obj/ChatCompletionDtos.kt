/*
 * Copyright (c) 2026 You Apps
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package net.youapps.translation_engines.ai.obj

import kotlinx.serialization.Serializable

// Used for request messages, response messages and SSE chunk deltas
@Serializable
data class ChatMessage(
    val role: String = "",
    val content: String? = null
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val stream: Boolean
)

// Used for both the non-streaming response body and a single SSE chunk
@Serializable
data class ChatCompletionResponse(
    val choices: List<ChatChoice> = emptyList()
)

@Serializable
data class ChatChoice(
    val message: ChatMessage? = null,
    val delta: ChatMessage? = null
)

@Serializable
data class ChatModelsResponse(
    val data: List<ChatModel> = emptyList()
)

@Serializable
data class ChatModel(
    val id: String
)

@Serializable
data class ChatErrorResponse(
    val error: ChatError? = null
)

@Serializable
data class ChatError(
    val message: String? = null
)
