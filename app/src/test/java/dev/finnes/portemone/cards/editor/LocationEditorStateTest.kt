// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone.cards.editor

import androidx.compose.runtime.saveable.SaverScope
import dev.finnes.portemone.LocationOrigin
import dev.finnes.portemone.R
import dev.finnes.portemone.StoreLocation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocationEditorStateTest {
    private val original = StoreLocation("store", "Saved store", 55.6761, 12.5683,
        origin = LocationOrigin.IMPORTED, address = "Example Street 12")

    @Test
    fun delayedFixDoesNotOverwriteManualCoordinatesOrPin() = runBlocking {
        val edits: List<(LocationEditorState) -> Unit> = listOf(
            { it.editLatitude("55.7") },
            { it.editLongitude("12.6") },
            { it.placePin(55.7, 12.6) }
        )
        for (edit in edits) {
            val state = LocationEditorState(original, null)
            val fix = CompletableDeferred<EditorPosition>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) { state.locate { fix.await() } }
            assertTrue(state.locating)
            edit(state)
            val selected = state.draft
            fix.complete(EditorPosition(56.16, 10.20))
            job.join()
            assertEquals(selected, state.draft)
            assertNull(state.address)
            assertFalse(state.locating)
        }
    }

    @Test
    fun selectingSearchResultWinsOverPendingFixAndPreservesExistingName() = runBlocking {
        val state = LocationEditorState(original, null)
        val fix = CompletableDeferred<EditorPosition>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { state.locate { fix.await() } }
        state.search = "Another store"
        state.selectResult(StoreSearchResult("Example Street 24", 55.7, 12.6))
        fix.complete(EditorPosition(56.16, 10.20))
        job.join()
        assertEquals(original.copy(latitude = 55.7, longitude = 12.6, address = "Example Street 24"), state.draft)
    }

    @Test
    fun freshFixUpdatesPositionWithoutChangingIdentityOrOrigin() = runBlocking {
        val state = LocationEditorState(original, null)
        state.locate { EditorPosition(55.7, 12.6) }
        assertEquals(original.copy(latitude = 55.7, longitude = 12.6, address = null), state.draft)
        assertFalse(state.locating)
        assertNull(state.error)
    }

    @Test
    fun cancelledFixLeavesDraftUnchangedAndDoesNotShowFailure() = runBlocking {
        val state = LocationEditorState(original, null)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.locate { CompletableDeferred<EditorPosition>().await() }
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(state.locating)
        assertNull(state.error)
        assertEquals(original, state.draft)
    }

    @Test
    fun failedFixKeepsDraftAndAllowsRetry() = runBlocking {
        val state = LocationEditorState(original, null)
        state.locate { error("Unavailable") }
        assertEquals(original, state.draft)
        assertFalse(state.locating)
        assertEquals(R.string.could_not_find_your_location_try_again_search_an, state.error)
        state.locate { EditorPosition(55.7, 12.6) }
        assertNull(state.error)
        assertEquals(55.7, state.draft.latitude, 0.0)
    }

    @Test
    fun searchHandlesSelectionEmptyResultsFailureAndCancellation() = runBlocking {
        val state = LocationEditorState(null, null)
        state.search = "  Example store  "
        val result = StoreSearchResult("Example Street 12", 55.6761, 12.5683)
        state.find { query ->
            assertEquals("Example store", query)
            listOf(result)
        }
        state.selectResult(state.searchResults.single())
        assertEquals("Example store", state.name)
        assertEquals(result.label, state.address)
        assertTrue(state.valid)
        assertTrue(state.searchResults.isEmpty())
        state.find { listOf(result) }
        state.find { emptyList() }
        assertTrue(state.searchResults.isEmpty())
        assertEquals(R.string.no_result_try_a_street_and_city_or_place, state.searchError)
        state.find { listOf(result) }
        state.find { error("Unavailable") }
        assertTrue(state.searchResults.isEmpty())
        assertFalse(state.searching)
        assertEquals(R.string.search_is_unavailable_place_the_pin_on_the_map, state.searchError)
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.find { CompletableDeferred<List<StoreSearchResult>>().await() }
        }
        assertTrue(state.searching)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(state.searching)
        assertNull(state.searchError)
    }

    @Test
    fun validationRejectsInvalidCoordinatesAndRadiusWithoutChangingRawInput() {
        val state = LocationEditorState(original, null)
        assertFalse(state.dirty)
        for (value in listOf("", "-", "NaN", "Infinity", "91")) {
            state.editLatitude(value)
            assertFalse(state.valid)
            assertEquals(value, state.latitude)
        }
        state.editLatitude("55.6761")
        state.editLongitude("181")
        assertFalse(state.valid)
        state.editLongitude("12.5683")
        for (value in listOf("49", "1001", "NaN", "Infinity")) {
            state.radius = value
            assertFalse(state.valid)
        }
        for (value in listOf("50", "1000")) {
            state.radius = value
            assertTrue(state.valid)
        }
        assertTrue(state.dirty)
    }

    @Test
    fun savedStateRetainsRawDraftAndIdentityButNotPendingOperations() = runBlocking {
        val state = LocationEditorState(null, original)
        assertFalse(state.dirty)
        state.name = "Draft store"
        state.editLatitude("-")
        state.radius = "075"
        state.search = "Unfinished search"
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            state.find { CompletableDeferred<List<StoreSearchResult>>().await() }
        }
        val saver = LocationEditorState.saver(null, original)
        val scope = SaverScope { true }
        val saved = with(saver) { scope.save(state) }
        val restored = saver.restore(requireNotNull(saved))!!
        job.cancelAndJoin()
        assertEquals(state.id, restored.id)
        assertEquals("Draft store", restored.name)
        assertEquals("-", restored.latitude)
        assertEquals("075", restored.radius)
        assertEquals("Unfinished search", restored.search)
        assertFalse(restored.valid)
        assertTrue(restored.dirty)
        assertFalse(restored.searching)
        assertFalse(restored.locating)
    }
}
