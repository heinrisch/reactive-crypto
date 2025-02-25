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

package com.njkim.reactivecrypto.kraken

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.njkim.reactivecrypto.core.ExchangeJsonObjectMapper
import com.njkim.reactivecrypto.core.common.model.ExchangeVendor
import com.njkim.reactivecrypto.core.common.model.currency.CurrencyPair
import com.njkim.reactivecrypto.core.common.model.order.OrderBook
import com.njkim.reactivecrypto.core.common.model.order.OrderBookUnit
import com.njkim.reactivecrypto.core.common.model.order.TickData
import com.njkim.reactivecrypto.core.common.model.order.TradeSideType
import com.njkim.reactivecrypto.core.websocket.AbstractExchangeWebsocketClient
import com.njkim.reactivecrypto.kraken.model.KrakenOrderBook
import com.njkim.reactivecrypto.kraken.model.KrakenOrderBookUnit
import com.njkim.reactivecrypto.kraken.model.KrakenSubscriptionStatus
import com.njkim.reactivecrypto.kraken.model.KrakenTickDataWrapper
import mu.KotlinLogging
import reactor.core.publisher.Flux
import reactor.netty.http.client.HttpClient
import java.math.BigDecimal
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.CRC32

/**
 * Kraken Websockets Public API Version 0.1.1
 *
 * All messages sent and received via WebSockets are encoded in JSON format
 * All floating point fields (including timestamps) are quoted to preserve precision.
 * Format of each tradeable pair is A/B, where A and B are ISO 4217-A3 for standardized assets and popular unique symbol if not standardized.
 * Timestamps should not be considered unique and not be considered as aliases for transaction ids. Also, the granularity of timestamps is not representative of transaction rates.
 *
 */
class KrakenWebsocketClient : AbstractExchangeWebsocketClient() {
    private val log = KotlinLogging.logger {}

    private val baseUri = "wss://ws.kraken.com"

    private val objectMapper: ObjectMapper = createJsonObjectMapper().objectMapper()

    override fun createJsonObjectMapper(): ExchangeJsonObjectMapper {
        return KrakenJsonObjectMapper()
    }

    override fun createDepthSnapshot(subscribeTargets: List<CurrencyPair>): Flux<OrderBook> {
        val krakenOrderBookMap: MutableMap<CurrencyPair, KrakenOrderBook> = ConcurrentHashMap()
        val channelCurrencyPairMap: MutableMap<Int, CurrencyPair> = ConcurrentHashMap()

        fun combineKrakenOrderBookUnits(
            old: List<KrakenOrderBookUnit>,
            new: List<KrakenOrderBookUnit>
        ): List<KrakenOrderBookUnit> {
            val map = old.map { it.price.stripTrailingZeros() to it }.toMap().toMutableMap()
            new.forEach { newUnit ->
                map.compute(newUnit.price.stripTrailingZeros()) { _, curUnit ->
                    if (newUnit.timestamp <= curUnit?.timestamp ?: ZonedDateTime.now()
                            .minusYears(1)
                    ) throw Exception("Old unit newer than new unit ${newUnit.timestamp} | ${curUnit?.timestamp}")
                    when {
                        newUnit.volume <= BigDecimal.ZERO -> null
                        curUnit == null -> newUnit
                        else -> newUnit
                    }
                }
            }
            return map.values.toList()
        }

        val subscribeSymbols = subscribeTargets
            .map { "${it.baseCurrency.symbol}/${it.quoteCurrency.symbol}".toUpperCase() }
            .map { "\"$it\"" }
            .joinToString(",", "[", "]")

        val subscribeMessage = "{" +
                "\"event\": \"subscribe\"," +
                "\"pair\": $subscribeSymbols," +
                "\"subscription\": {\"name\":\"book\"}" +
                "}"

        return HttpClient.create()
            .wiretap(log.isDebugEnabled)
            .websocket()
            .uri(baseUri)
            .handle { inbound, outbound ->
                outbound.sendString(Flux.just(subscribeMessage))
                    .then()
                    .thenMany(inbound.receive().asString())
            }
            .doOnNext {
                if (it.contains("\"event\":\"subscriptionStatus")) {
                    val subscribeStatus = objectMapper.readValue<KrakenSubscriptionStatus>(it)
                    channelCurrencyPairMap.put(subscribeStatus.channelID, subscribeStatus.pair)
                }
            }
            .filter { !it.contains("\"event\":\"") }
            .map { input ->
                val newOrderBook = objectMapper.readValue<KrakenOrderBook>(input)
                val currencyPair = channelCurrencyPairMap[newOrderBook.channelID]!!
                return@map (if (newOrderBook.isSnapshot) newOrderBook.also { println("New snapshot") }
                else {
                    with(krakenOrderBookMap.getValue(currencyPair)) {
                        KrakenOrderBook(
                            channelID,
                            asks = combineKrakenOrderBookUnits(asks, newOrderBook.asks).sortedBy { it.price }.take(10),
                            bids = combineKrakenOrderBookUnits(bids, newOrderBook.bids).sortedByDescending { it.price }
                                .take(10),
                            isSnapshot = false,
                            checksum = newOrderBook.checksum
                        )
                    }
                }).also {
                    krakenOrderBookMap[currencyPair] = it
                    validateChecksum(it)
                }
            }
            .map { krakenOrderBook ->
                val now = ZonedDateTime.now()
                OrderBook(
                    "$now",
                    channelCurrencyPairMap[krakenOrderBook.channelID]!!,
                    now,
                    ExchangeVendor.KRAKEN,
                    krakenOrderBook.bids.map { OrderBookUnit(it.price, it.volume, TradeSideType.BUY, null) },
                    krakenOrderBook.asks.map { OrderBookUnit(it.price, it.volume, TradeSideType.SELL, null) }
                )
            }
            .doOnError { log.error(it.message, it) }
            .doFinally {
                // cleanup memory limit orderBook when disconnected
                krakenOrderBookMap.clear()
                channelCurrencyPairMap.clear()
            }
    }

    private fun validateChecksum(order: KrakenOrderBook) {
        order.checksum ?: return // Some things don't contain checksum

        fun List<KrakenOrderBookUnit>.orderToString() =
            joinToString("") {
                fun String.clean() = replace(".", "").dropWhile { c -> c == '0' }
                it.price.toString().clean() +
                it.volume.toString().clean()
            }

        val sum = order.asks.orderToString() + order.bids.orderToString()
        val calculatedChecksum = CRC32().apply { update(sum.toByteArray()) }.value
        if (order.checksum != calculatedChecksum.toString()) throw Exception("Checksum mismatch: ${order.checksum} != $calculatedChecksum")
    }

    override fun createTradeWebsocket(subscribeTargets: List<CurrencyPair>): Flux<TickData> {
        val channelCurrencyPairMap: MutableMap<Int, CurrencyPair> = ConcurrentHashMap()

        val subscribeSymbols = subscribeTargets
            .map { "${it.baseCurrency.symbol}/${it.quoteCurrency.symbol}".toUpperCase() }
            .map { "\"$it\"" }
            .joinToString(",", "[", "]")

        val subscribeMessage = "{" +
                "\"event\": \"subscribe\"," +
                "\"pair\": $subscribeSymbols," +
                "\"subscription\": {\"name\":\"trade\"}" +
                "}"

        return HttpClient.create()
            .wiretap(log.isDebugEnabled)
            .websocket()
            .uri(baseUri)
            .handle { inbound, outbound ->
                outbound.sendString(Flux.just(subscribeMessage))
                    .then()
                    .thenMany(inbound.receive().asString())
            }
            .doOnNext {
                if (it.contains("\"event\":\"subscriptionStatus")) {
                    val subscribeStatus = objectMapper.readValue<KrakenSubscriptionStatus>(it)
                    channelCurrencyPairMap.put(subscribeStatus.channelID, subscribeStatus.pair)
                }
            }
            .filter { !it.contains("\"event\":\"") }
            .map { objectMapper.readValue<KrakenTickDataWrapper>(it) }
            .flatMapIterable {
                it.data.map { krakenTickData ->
                    TickData(
                        "${krakenTickData.time}", // FIXME time is not enough to use uniqueId
                        krakenTickData.time,
                        krakenTickData.price,
                        krakenTickData.volume,
                        channelCurrencyPairMap[it.channelId]!!,
                        ExchangeVendor.KRAKEN,
                        krakenTickData.tradeSideType
                    )
                }
            }
            .doOnError { log.error(it.message, it) }
    }
}
