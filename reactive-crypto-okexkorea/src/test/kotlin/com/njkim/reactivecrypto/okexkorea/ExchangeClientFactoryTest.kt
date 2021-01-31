/*
 * Copyright 2019 namjug-kim
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.njkim.reactivecrypto.okex

import com.njkim.reactivecrypto.core.ExchangeClientFactory
import com.njkim.reactivecrypto.core.websocket.ExchangePublicWebsocketClient
import com.njkim.reactivecrypto.core.common.model.ExchangeVendor
import com.njkim.reactivecrypto.okexkorea.OkexKoreaWebsocketClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.Test

class ExchangeClientFactoryTest {
    @Test
    fun `create okex korea websocket client`() {
        val exchangeWebsocketClient = ExchangeClientFactory.publicWebsocket(ExchangeVendor.OKEX_KOREA)

        assertThat(exchangeWebsocketClient).isNotNull
        assertThat(exchangeWebsocketClient).isInstanceOf(ExchangePublicWebsocketClient::class.java)
        assertThat(exchangeWebsocketClient).isExactlyInstanceOf(OkexKoreaWebsocketClient::class.java)
    }
}
