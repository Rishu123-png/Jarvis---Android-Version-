package com.jarvis.assistant.device

import android.Manifest
import android.content.ContentProviderOperation
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jarvis.assistant.voice.Contacts
import com.jarvis.assistant.voice.VoiceMemory

/**
 * Saving a person, not just dialling them.
 *
 * "save this number as Amit" is one of those small requests that makes an assistant feel like it
 * lives on the phone rather than on a server. It is also the only way a number read out in a
 * message ever becomes usable later, so it is worth doing properly:
 *
 *  - the contact is created through `ContactsContract` (the real address book),
 *  - the number is also remembered in JARVIS's own alias store, so "call Amit" works immediately
 *    even before the contacts app has indexed the row,
 *  - an existing name is *not* silently overwritten — [save] reports that instead.
 */
object ContactsWrite {

    data class Outcome(val ok: Boolean, val detail: String)

    fun canWrite(context: Context): Boolean = ContextCompat.checkSelfPermission(
        context.applicationContext,
        Manifest.permission.WRITE_CONTACTS,
    ) == PackageManager.PERMISSION_GRANTED

    suspend fun save(context: Context, name: String, number: String): Outcome =
        withContext(Dispatchers.IO) {
            val cleanName = name.trim()
            val digits = number.filter { it.isDigit() || it == '+' }
            if (cleanName.isBlank()) return@withContext Outcome(false, "No name given")
            if (digits.count { it.isDigit() } < 6) return@withContext Outcome(false, "That number looks incomplete")
            if (!canWrite(context)) {
                return@withContext Outcome(false, "Contact access is read-only — allow editing in Settings → Access")
            }

            val existing = try {
                Contacts.resolve(context, cleanName)
            } catch (t: Throwable) {
                null
            }
            if (existing != null && existing.how == "contacts") {
                VoiceMemory.rememberAlias(context, cleanName, digits)
                return@withContext Outcome(
                    false,
                    "${existing.name} is already saved with ${existing.number}. I did not change it.",
                )
            }

            val ops = arrayListOf<ContentProviderOperation>()
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_TYPE, null)
                    .withValue(ContactsContract.RawContacts.ACCOUNT_NAME, null)
                    .build(),
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, cleanName)
                    .build(),
            )
            ops.add(
                ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(
                        ContactsContract.Data.MIMETYPE,
                        ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
                    )
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, digits)
                    .withValue(
                        ContactsContract.CommonDataKinds.Phone.TYPE,
                        ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
                    )
                    .build(),
            )

            try {
                context.contentResolver.applyBatch(ContactsContract.AUTHORITY, ops)
                VoiceMemory.rememberAlias(context, cleanName, digits)
                Contacts.invalidate()
                Outcome(true, "Saved $cleanName.")
            } catch (t: Throwable) {
                Outcome(false, "The contacts app refused the new entry.")
            }
        }
}
