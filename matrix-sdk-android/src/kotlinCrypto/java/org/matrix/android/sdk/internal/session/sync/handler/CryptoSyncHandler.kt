/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.sync.handler

import dagger.Lazy
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_OLM
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.model.MXEventDecryptionResult
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.OlmEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.internal.crypto.DefaultCryptoService
import org.matrix.android.sdk.internal.crypto.tasks.toDeviceTracingId
import org.matrix.android.sdk.internal.crypto.verification.DefaultVerificationService
import org.matrix.android.sdk.internal.session.sync.ProgressReporter
import timber.log.Timber
import javax.inject.Inject

private val loggerTag = LoggerTag("CryptoSyncHandler", LoggerTag.CRYPTO)

internal class CryptoSyncHandler @Inject constructor(
        private val cryptoService: Lazy<DefaultCryptoService>,
        private val verificationService: DefaultVerificationService
) {

    suspend fun handleToDevice(eventList: List<Event>, progressReporter: ProgressReporter? = null) {
        val total = eventList.size
        eventList.filter { isSupportedToDevice(it) }
                .forEachIndexed { index, event ->
                    progressReporter?.reportProgress(index * 100F / total)
                    // Decrypt event if necessary
                    Timber.tag(loggerTag.value).d("To device event msgid:${event.toDeviceTracingId()}")
                    decryptToDeviceEvent(event, null)

                    if (event.getClearType() == EventType.MESSAGE &&
                            event.getClearContent()?.toModel<MessageContent>()?.msgType == "m.bad.encrypted") {
                        Timber.tag(loggerTag.value).e("handleToDeviceEvent() : Warning: Unable to decrypt to-device event : ${event.content}")
                    } else {
                        Timber.tag(loggerTag.value).d("received to-device ${event.getClearType()} from:${event.senderId} msgid:${event.toDeviceTracingId()}")
                        verificationService.onToDeviceEvent(event)
                        cryptoService.get().onToDeviceEvent(event)
                    }
                }
    }

    private val unsupportedPlainToDeviceEventTypes = listOf(
            EventType.ROOM_KEY,
            EventType.FORWARDED_ROOM_KEY,
            EventType.SEND_SECRET
    )

    private fun isSupportedToDevice(event: Event): Boolean {
        val algorithm = event.content?.get("algorithm") as? String
        val type = event.type.orEmpty()
        return if (event.isEncrypted()) {
            algorithm == MXCRYPTO_ALGORITHM_OLM
        } else {
            // some clear events are not allowed
            type !in unsupportedPlainToDeviceEventTypes
        }.also {
            if (!it) {
                Timber.tag(loggerTag.value)
                        .w("Ignoring unsupported to device event ${event.type} alg:${algorithm}")
            }
        }
    }

    /**
     * Decrypt an encrypted event.
     *
     * @param event the event to decrypt
     * @param timelineId the timeline identifier
     * @return true if the event has been decrypted
     */
    private suspend fun decryptToDeviceEvent(event: Event, timelineId: String?): Boolean {
        Timber.v("## CRYPTO | decryptToDeviceEvent")
        if (event.getClearType() == EventType.ENCRYPTED) {
            var result: MXEventDecryptionResult? = null
            try {
                result = cryptoService.get().decryptEvent(event, timelineId ?: "")
            } catch (exception: MXCryptoError) {
                event.mCryptoError = (exception as? MXCryptoError.Base)?.errorType // setCryptoError(exception.cryptoError)
                val senderKey = event.content.toModel<OlmEventContent>()?.senderKey ?: "<unknown sender key>"
                // try to find device id to ease log reading
                val deviceId = cryptoService.get().getCryptoDeviceInfo(event.senderId!!).firstOrNull {
                    it.identityKey() == senderKey
                }?.deviceId ?: senderKey
                Timber.e("## CRYPTO | Failed to decrypt to device event from ${event.senderId}|$deviceId reason:<${event.mCryptoError ?: exception}>")
            } catch (failure: Throwable) {
                Timber.e(failure, "## CRYPTO | Failed to decrypt to device event from ${event.senderId}")
            }

            if (null != result) {
                event.mxDecryptionResult = OlmDecryptionResult(
                        payload = result.clearEvent,
                        senderKey = result.senderCurve25519Key,
                        keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                        forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                        verificationState = result.messageVerificationState,
                )
                return true
            } else {
                // Could happen for to device events
                // None of the known session could decrypt the message
                // In this case unwedging process might have been started (rate limited)
                Timber.e("## CRYPTO | ERROR NULL DECRYPTION RESULT from ${event.senderId}")
            }
        }

        return false
    }
}
