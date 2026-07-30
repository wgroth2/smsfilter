/*
 * Copyright (c) 2026 Bill Roth <bill.roth@gmail.com>
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holder nor the names of its contributors
 *    may be used to endorse or promote products derived from this software
 *    without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 */

package com.digiroth.smsfilter.data.remote

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Moshi models for the HubSpot CRM v3 API.
 *
 * These classes live in `com.digiroth.smsfilter.data.remote` deliberately: `proguard-rules.pro`
 * keeps that package wholesale. Moshi's generated adapters reach these fields by name, and R8
 * cannot see that relationship — so a model placed outside the kept package would be renamed in a
 * release build and fail to parse there while working perfectly in debug.
 *
 * Every class carries `@JsonClass(generateAdapter = true)` so KSP generates its adapter at build
 * time rather than Moshi reflecting over it at runtime.
 */

/**
 * One filter in a contact search.
 *
 * @property propertyName The HubSpot property to match against.
 * @property operator HubSpot search operator, e.g. `CONTAINS_TOKEN`.
 * @property value The value to match.
 */
@JsonClass(generateAdapter = true)
data class HubSpotFilter(
    @Json(name = "propertyName") val propertyName: String,
    @Json(name = "operator") val operator: String,
    @Json(name = "value") val value: String,
)

/**
 * A group of filters. Filters inside one group are ANDed; separate groups are ORed.
 *
 * @property filters The filters in this group.
 */
@JsonClass(generateAdapter = true)
data class HubSpotFilterGroup(
    @Json(name = "filters") val filters: List<HubSpotFilter>,
)

/**
 * Request body for `POST /crm/v3/objects/contacts/search`.
 *
 * @property filterGroups The search criteria.
 * @property properties Which contact properties to return. Kept minimal — this app only needs to
 *   know whether a match exists, and requesting fewer properties returns less personal data.
 * @property limit Maximum results. One is enough to answer "is this a known contact".
 */
@JsonClass(generateAdapter = true)
data class HubSpotSearchRequest(
    @Json(name = "filterGroups") val filterGroups: List<HubSpotFilterGroup>,
    @Json(name = "properties") val properties: List<String> = emptyList(),
    @Json(name = "limit") val limit: Int = 1,
)

/**
 * A contact returned by a search.
 *
 * @property id The HubSpot record id.
 * @property properties Requested properties, if any were asked for.
 */
@JsonClass(generateAdapter = true)
data class HubSpotContact(
    @Json(name = "id") val id: String? = null,
    @Json(name = "properties") val properties: Map<String, String?> = emptyMap(),
)

/**
 * Response body for a contact search or a contacts list call.
 *
 * Both fields default so a response omitting either still parses — HubSpot returns no `total` on
 * some list endpoints, and a strict model would throw where the app only needs to count results.
 *
 * @property total Total matches HubSpot found.
 * @property results The returned contacts.
 */
@JsonClass(generateAdapter = true)
data class HubSpotSearchResponse(
    @Json(name = "total") val total: Int = 0,
    @Json(name = "results") val results: List<HubSpotContact> = emptyList(),
) {
    /** Whether this response indicates at least one matching contact. */
    val hasMatch: Boolean
        get() = total > 0 || results.isNotEmpty()
}

/**
 * Response body for `GET /account-info/v3/details`, used to show the portal id once connected.
 *
 * @property portalId The HubSpot portal (account) id, or `null` if the token lacks the scope.
 */
@JsonClass(generateAdapter = true)
data class HubSpotAccountInfo(
    @Json(name = "portalId") val portalId: Long? = null,
)
