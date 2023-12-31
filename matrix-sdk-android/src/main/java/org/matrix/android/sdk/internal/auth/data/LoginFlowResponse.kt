/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.auth.data

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.auth.data.SsoIdentityProvider

@JsonClass(generateAdapter = true)
internal data class LoginFlowResponse(
        /**
         * The homeserver's supported login types.
         */
        @Json(name = "flows")
        val flows: List<LoginFlow>?
)

@JsonClass(generateAdapter = true)
internal data class LoginFlow(
        /**
         * The login type. This is supplied as the type when logging in.
         */
        @Json(name = "type")
        val type: String?,

        /**
         * Augments m.login.sso flow discovery definition to include metadata on the supported IDPs
         * the client can show a button for each of the supported providers.
         * See MSC #2858
         */
        @Json(name = "identity_providers")
        val ssoIdentityProvider: List<SsoIdentityProvider>? = null,

        /**
         * Whether this login flow is preferred for OIDC-aware clients.
         *
         * See [MSC3824](https://github.com/matrix-org/matrix-spec-proposals/pull/3824)
         */
        @Json(name = "org.matrix.msc3824.delegated_oidc_compatibility")
        val delegatedOidcCompatibilty: Boolean? = null,

        /**
         * Whether a login flow of type m.login.token could accept a token issued using /login/get_token.
         *
         * See https://spec.matrix.org/v1.7/client-server-api/#post_matrixclientv1loginget_token
         */
        @Json(name = "get_login_token")
        val getLoginToken: Boolean? = null
)
