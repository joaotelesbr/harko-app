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

package org.matrix.android.sdk.internal.crypto.keysbackup

import kotlinx.coroutines.suspendCancellableCoroutine
import org.junit.Assert
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupService
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupState
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupStateListener
import org.matrix.android.sdk.common.CommonTestHelper
import org.matrix.android.sdk.common.CryptoTestHelper
import org.matrix.android.sdk.common.assertDictEquals
import org.matrix.android.sdk.common.assertListEquals
import org.matrix.android.sdk.internal.crypto.MegolmSessionData
import kotlin.coroutines.resume

internal class KeysBackupTestHelper(
        private val testHelper: CommonTestHelper,
        private val cryptoTestHelper: CryptoTestHelper
) {

    fun waitForKeybackUpBatching() {
        Thread.sleep(400)
    }

    /**
     * Common initial condition
     * - Do an e2e backup to the homeserver
     * - Log Alice on a new device, and wait for its keysBackup object to be ready (in state NotTrusted)
     *
     * @param password optional password
     */
    suspend fun createKeysBackupScenarioWithPassword(password: String?): KeysBackupScenarioData {
        val cryptoTestData = cryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        waitForKeybackUpBatching()

//        val cryptoStore = (cryptoTestData.firstSession.cryptoService().keysBackupService() as DefaultKeysBackupService).store
        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

//        val aliceKeys = cryptoStore.inboundGroupSessionsToBackup(100)

        // - Do an e2e backup to the homeserver
        val prepareKeysBackupDataResult = prepareAndCreateKeysBackupData(keysBackup, password)

        testHelper.retryPeriodically {
            keysBackup.getTotalNumbersOfKeys() == keysBackup.getTotalNumbersOfBackedUpKeys()
        }
        val totalNumbersOfBackedUpKeys =  cryptoTestData.firstSession.cryptoService().keysBackupService().getTotalNumbersOfBackedUpKeys()

        Assert.assertEquals(2, totalNumbersOfBackedUpKeys)

        val aliceUserId = cryptoTestData.firstSession.myUserId

        // - Log Alice on a new device
        val aliceSession2 = testHelper.logIntoAccount(aliceUserId, KeysBackupTestConstants.defaultSessionParamsWithInitialSync)

        // Test check: aliceSession2 has no keys at login
        val inboundGroupSessionCount =  aliceSession2.cryptoService().inboundGroupSessionsCount(false)
        Assert.assertEquals(0, inboundGroupSessionCount)

        // Wait for backup state to be NotTrusted
        waitForKeysBackupToBeInState(aliceSession2, KeysBackupState.NotTrusted)

        stateObserver.stopAndCheckStates(null)

        return KeysBackupScenarioData(cryptoTestData,
                totalNumbersOfBackedUpKeys,
                prepareKeysBackupDataResult,
                aliceSession2)
    }

    suspend fun prepareAndCreateKeysBackupData(
            keysBackup: KeysBackupService,
            password: String? = null
    ): PrepareKeysBackupDataResult {
        val stateObserver = StateObserver(keysBackup)

        val megolmBackupCreationInfo = keysBackup.prepareKeysBackupVersion(password, null)

        Assert.assertNotNull(megolmBackupCreationInfo)

        Assert.assertFalse("Key backup should not be enabled before creation", keysBackup.isEnabled())

        // Create the version
        val keysVersion =
            keysBackup.createKeysBackupVersion(megolmBackupCreationInfo)

        Assert.assertNotNull("Key backup version should not be null", keysVersion.version)

        // Backup must be enable now
        Assert.assertTrue(keysBackup.isEnabled())

        stateObserver.stopAndCheckStates(null)
        return PrepareKeysBackupDataResult(megolmBackupCreationInfo, keysVersion.version)
    }

    /**
     * As KeysBackup is doing asynchronous call to update its internal state, this method help to wait for the
     * KeysBackup object to be in the specified state
     */
    suspend fun waitForKeysBackupToBeInState(session: Session, state: KeysBackupState) {
        // If already in the wanted state, return
        val keysBackupService = session.cryptoService().keysBackupService()
        if (keysBackupService.getState() == state) {
            return
        }

        // Else observe state changes
        suspendCancellableCoroutine<Unit> { continuation ->
            val listener = object : KeysBackupStateListener {
                override fun onStateChange(newState: KeysBackupState) {
                    if (newState == state) {
                        keysBackupService.removeListener(this)
                        continuation.resume(Unit)
                    }
                }
            }
            keysBackupService.addListener(listener)
            continuation.invokeOnCancellation { keysBackupService.removeListener(listener) }
        }
    }

    internal fun assertKeysEquals(keys1: MegolmSessionData?, keys2: MegolmSessionData?) {
        Assert.assertNotNull(keys1)
        Assert.assertNotNull(keys2)

        Assert.assertEquals(keys1?.algorithm, keys2?.algorithm)
        Assert.assertEquals(keys1?.roomId, keys2?.roomId)
        // No need to compare the shortcut
        // assertEquals(keys1?.sender_claimed_ed25519_key, keys2?.sender_claimed_ed25519_key)
        Assert.assertEquals(keys1?.senderKey, keys2?.senderKey)
        Assert.assertEquals(keys1?.sessionId, keys2?.sessionId)
        Assert.assertEquals(keys1?.sessionKey, keys2?.sessionKey)

        assertListEquals(keys1?.forwardingCurve25519KeyChain, keys2?.forwardingCurve25519KeyChain)
        assertDictEquals(keys1?.senderClaimedKeys, keys2?.senderClaimedKeys)
    }

    /**
     * Common restore success check after [KeysBackupTestHelper.createKeysBackupScenarioWithPassword]:
     * - Imported keys number must be correct
     * - The new device must have the same count of megolm keys
     * - Alice must have the same keys on both devices
     */
    suspend fun checkRestoreSuccess(
            testData: KeysBackupScenarioData,
            total: Int,
            imported: Int
    ) {
        // - Imported keys number must be correct
        Assert.assertEquals(testData.aliceKeysCount, total)
        Assert.assertEquals(total, imported)

        // - The new device must have the same count of megolm keys
        val inboundGroupSessionCount = testData.aliceSession2.cryptoService().inboundGroupSessionsCount(false)

        Assert.assertEquals(testData.aliceKeysCount, inboundGroupSessionCount)

        // - Alice must have the same keys on both devices
        // TODO can't access internals as we can switch from rust/kotlin
//        for (aliceKey1 in testData.aliceKeys) {
//            val aliceKey2 = (testData.aliceSession2.cryptoService().keysBackupService() as DefaultKeysBackupService).store
//                    .getInboundGroupSession(aliceKey1.safeSessionId!!, aliceKey1.senderKey!!)
//            Assert.assertNotNull(aliceKey2)
//            assertKeysEquals(aliceKey1.exportKeys(), aliceKey2!!.exportKeys())
//        }
    }
}
