// SPDX-FileCopyrightText: 2026 Sofie Finnes <sofie+git@finnes.dev>
// SPDX-License-Identifier: EUPL-1.2

package dev.finnes.portemone

import dev.finnes.portemone.cards.CardWriter

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.finnes.portemone.imports.PKPassImporter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal data class CardManagementState(
    val cards: List<Card> = emptyList(),
    val loading: Boolean = true,
    val busy: Boolean = false,
    val review: PKPassImporter.Package? = null,
    val existing: Card? = null,
    val message: String? = null,
)

internal class CardManagementModel(application: Application) : AndroidViewModel(application) {
    private val repository = LocalCardRepository.get(application)
    private val writer = CardWriter(application)
    private val mutable = MutableStateFlow(CardManagementState())
    val state = mutable.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private suspend fun reload() {
        try {
            mutable.value = mutable.value.copy(
                cards = withContext(Dispatchers.IO) { repository.cards() },
                loading = false
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutable.value = mutable.value.copy(
                loading = false,
                message = getApplication<Application>().getString(R.string.could_not_read_saved_cards_try_reopening_this_screen)
            )
        }
    }

    fun import(uri: Uri) {
        if (mutable.value.busy || mutable.value.review != null) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                val pass = withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw IllegalArgumentException(getApplication<Application>().getString(R.string.the_file_could_not_be_opened))
                    PKPassImporter.read(stream, getApplication<Application>().resources.configuration.locales[0]) { index ->
                        getApplication<Application>().getString(R.string.imported_location_name, index)
                    }
                }
                val cards = withContext(Dispatchers.IO) { repository.cards() }
                val existing = cards.find { card ->
                    card.source?.let { source ->
                        source.format == pass.card.source?.format && source.issuerId == pass.card.source?.issuerId && source.recordId == pass.card.source?.recordId
                    } == true
                }
                mutable.value =
                    mutable.value.copy(cards = cards, review = pass, existing = existing)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                mutable.value =
                    mutable.value.copy(message = error.userMessage(getApplication<Application>().resources, R.string.could_not_read_this_pass_check_the_file_and))
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun exportOriginal(cardId: String, destination: Uri) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val bytes = repository.originalPackage(cardId)
                        ?: throw IllegalStateException(getApplication<Application>().getString(R.string.no_original_package))
                    val stream = getApplication<Application>().contentResolver.openOutputStream(
                        destination,
                        "wt"
                    )
                        ?: throw IllegalStateException(getApplication<Application>().getString(R.string.could_not_open_destination))
                    stream.use { it.write(bytes) }
                }
                mutable.value =
                    mutable.value.copy(message = getApplication<Application>().getString(R.string.original_pass_exported_local_edits_are_not_included_in))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.value =
                    mutable.value.copy(message = getApplication<Application>().getString(R.string.could_not_export_the_original_pass_try_another_destination))
            } finally {
                mutable.value = mutable.value.copy(busy = false)
            }
        }
    }

    fun dismissReview() {
        if (!mutable.value.busy) mutable.value = mutable.value.copy(review = null, existing = null)
    }

    fun clearMessage() {
        mutable.value = mutable.value.copy(message = null)
    }

    fun acceptImport(keepLocations: Boolean, acceptedRisk: Boolean, onImported: () -> Unit = {}) {
        val pass = mutable.value.review ?: return
        if (pass.verification.warnings.isNotEmpty() && !acceptedRisk) return
        val existing = mutable.value.existing
        val card = pass.card.copy(
            id = existing?.id ?: pass.card.id, archived = existing?.archived ?: false,
            stores = if (existing != null && keepLocations) existing.stores else pass.card.stores
        )
        save(card, pass.bytes, existing) { dismissReview(); onImported() }
    }

    fun save(
        card: Card,
        bytes: ByteArray? = null,
        expected: Card? = null,
        onSaved: () -> Unit = {}
    ) {
        if (mutable.value.busy) return
        val normalized = card.copy(
            brandName = card.brandName.trim(),
            cardName = card.cardName.trim(),
            stores = card.stores.map { it.copy(name = it.name.trim()) })
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            var saved = false
            try {
                writer.saveCard(normalized, bytes, expected); saved = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                saved = withContext(Dispatchers.IO) {
                    repository.cards().find { it.id == card.id } == normalized
                }
                mutable.value = mutable.value.copy(
                    message = if (saved) getApplication<Application>().getString(R.string.card_saved_nearby_cards_could_not_update_refresh_nearby)
                    else error.userMessage(getApplication<Application>().resources, R.string.could_not_save_this_card_try_again)
                )
            } finally {
                reload()
                mutable.value = mutable.value.copy(busy = false)
            }
            if (saved) onSaved()
        }
    }

    fun archive(card: Card) {
        save(card.copy(archived = !card.archived), expected = card) {
            if (mutable.value.message == null) mutable.value = mutable.value.copy(
                message =
                    if (card.archived) getApplication<Application>().getString(R.string.card_restored) else getApplication<Application>().getString(R.string.card_archived_restore_it_from_archived)
            )
        }
    }

    fun delete(card: Card, onDeleted: () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true, message = null)
        viewModelScope.launch {
            try {
                writer.deleteCard(card); onDeleted()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (withContext(Dispatchers.IO) { repository.cards().none { it.id == card.id } }) {
                    mutable.value =
                        mutable.value.copy(message = getApplication<Application>().getString(R.string.card_deleted_nearby_cards_could_not_update_refresh_nearby))
                    onDeleted()
                } else mutable.value =
                    mutable.value.copy(message = getApplication<Application>().getString(R.string.could_not_delete_this_card_try_again))
            } finally {
                reload(); mutable.value = mutable.value.copy(busy = false)
            }
        }
    }
}
