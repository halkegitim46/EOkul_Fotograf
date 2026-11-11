package com.eokul.studio

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.Properties

/**
 * roster.json ile photos/ klasörünün birlikte değiştirilmesi için yerel işlem günlüğü.
 * İşlem yarıda kesilirse bir sonraki açılışta eski veri geri getirilir.
 */
object RestoreTransaction {
    private const val JOURNAL = "restore.transaction"
    private const val NEW_PHOTOS = "photos.new"
    private const val OLD_PHOTOS = "photos.restore.old"
    private const val OLD_ROSTER = "roster.json.restore.old"
    private const val OLD_ROSTER_BAK = "roster.json.bak.restore.old"
    private const val STATE_PREPARED = "PREPARED"
    private const val STATE_COMMITTED = "COMMITTED"

    @Throws(IOException::class)
    fun commit(context: Context, store: RosterStore, stagedPhotos: File, stagedRoster: File) {
        val base = context.filesDir
        val photos = File(base, "photos")
        val oldPhotos = File(base, OLD_PHOTOS)
        val oldRoster = File(base, OLD_ROSTER)
        val rosterBak = File(base, "roster.json.bak")
        val oldRosterBak = File(base, OLD_ROSTER_BAK)
        val roster = File(base, "roster.json")
        val journal = File(base, JOURNAL)
        val hadPhotos = photos.exists()
        val hadRoster = roster.exists()
        val hadRosterBak = rosterBak.exists()

        oldPhotos.deleteRecursively()
        oldRoster.delete()
        oldRosterBak.delete()
        writeJournal(journal, STATE_PREPARED, hadPhotos, hadRoster, hadRosterBak)

        try {
            if (hadRoster) copySynced(roster, oldRoster)
            if (hadRosterBak) copySynced(rosterBak, oldRosterBak)
            if (hadPhotos && !photos.renameTo(oldPhotos)) {
                throw IOException("Mevcut fotoğraf klasörü güvenli alana taşınamadı")
            }
            if (stagedPhotos.exists() && !stagedPhotos.renameTo(photos)) {
                throw IOException("Yeni fotoğraf klasörü etkinleştirilemedi")
            }

            val rosterError = store.commitPrepared(stagedRoster)
            if (rosterError != null) throw IOException("Öğrenci listesi kaydedilemedi: $rosterError")
            // Restore sonrası .bak de etkin roster ile aynı snapshot olmalı; aksi halde yeni photos/ ile
            // eski roster yedeği birlikte kalabilir.
            val newBakTmp = File(base, "roster.json.bak.new")
            copySynced(roster, newBakTmp)
            replaceFile(newBakTmp, rosterBak)

            writeJournal(journal, STATE_COMMITTED, hadPhotos, hadRoster, hadRosterBak)
            cleanup(base)
        } catch (e: Exception) {
            try {
                recover(context)
            } catch (recovery: Exception) {
                throw IOException(
                    "Geri yükleme başarısız: ${e.message}. Otomatik geri alma da tamamlanamadı: ${recovery.message}. " +
                        "Kurtarma dosyaları silinmeden korundu.", e
                )
            }
            throw e
        }
    }

    /** Uygulama açılışında çağrılır. PREPARED işlem varsa eski veri geri alınır. */
    @Throws(IOException::class)
    fun recover(context: Context) {
        val base = context.filesDir
        val journal = File(base, JOURNAL)
        if (!journal.exists()) return
        val props = Properties()
        FileInputStream(journal).use { props.load(it) }
        val state = props.getProperty("state") ?: throw IOException("Geri yükleme işlem günlüğü okunamadı")
        if (state == STATE_COMMITTED) {
            cleanup(base)
            return
        }
        if (state != STATE_PREPARED) throw IOException("Bilinmeyen geri yükleme işlem durumu: $state")

        val hadPhotos = props.getProperty("hadPhotos", "false").toBoolean()
        val hadRoster = props.getProperty("hadRoster", "false").toBoolean()
        val photos = File(base, "photos")
        val oldPhotos = File(base, OLD_PHOTOS)
        val roster = File(base, "roster.json")
        val oldRoster = File(base, OLD_ROSTER)
        val rosterBak = File(base, "roster.json.bak")
        val oldRosterBak = File(base, OLD_ROSTER_BAK)
        val hadRosterBak = props.getProperty("hadRosterBak", "false").toBoolean()

        if (hadPhotos) {
            if (oldPhotos.exists()) {
                if (photos.exists() && !photos.deleteRecursively()) throw IOException("Yeni fotoğraf klasörü kaldırılamadı")
                if (!oldPhotos.renameTo(photos)) throw IOException("Eski fotoğraf klasörü geri getirilemedi")
            }
            // oldPhotos yoksa eski klasör henüz taşınmamıştır; mevcut photos/ eski veridir.
        } else {
            if (photos.exists() && !photos.deleteRecursively()) throw IOException("Yeni fotoğraf klasörü geri alınamadı")
        }

        if (hadRoster) {
            if (oldRoster.exists()) replaceWithCopy(oldRoster, roster)
            // Eski roster kopyası henüz alınmadıysa roster.json değişmemiştir.
        } else {
            if (roster.exists() && !roster.delete()) throw IOException("Yeni öğrenci listesi geri alınamadı")
        }
        if (hadRosterBak) {
            if (oldRosterBak.exists()) replaceWithCopy(oldRosterBak, rosterBak)
        } else {
            if (rosterBak.exists() && !rosterBak.delete()) throw IOException("Yeni roster yedeği geri alınamadı")
        }
        cleanup(base)
    }

    private fun writeJournal(file: File, state: String, hadPhotos: Boolean, hadRoster: Boolean, hadRosterBak: Boolean) {
        val p = Properties().apply {
            setProperty("state", state)
            setProperty("hadPhotos", hadPhotos.toString())
            setProperty("hadRoster", hadRoster.toString())
            setProperty("hadRosterBak", hadRosterBak.toString())
            setProperty("updatedAt", System.currentTimeMillis().toString())
        }
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            p.store(out, "E-Okul Foto Studio restore transaction")
            out.flush()
            out.fd.sync()
        }
        try {
            java.nio.file.Files.move(
                tmp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            try {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                tmp.delete()
                throw IOException("Geri yükleme işlem günlüğü kaydedilemedi", e)
            }
        }
    }

    private fun copySynced(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
    }

    private fun replaceFile(source: File, target: File) {
        try {
            java.nio.file.Files.move(
                source.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            java.nio.file.Files.move(source.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun replaceWithCopy(source: File, target: File) {
        val tmp = File(target.parentFile, target.name + ".recovery.tmp")
        copySynced(source, tmp)
        try {
            java.nio.file.Files.move(
                tmp.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            try {
                java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } catch (e: Exception) {
                throw IOException("Eski öğrenci listesi geri getirilemedi", e)
            }
        }
    }

    private fun cleanup(base: File) {
        File(base, NEW_PHOTOS).deleteRecursively()
        File(base, OLD_PHOTOS).deleteRecursively()
        File(base, OLD_ROSTER).delete()
        File(base, OLD_ROSTER_BAK).delete()
        File(base, "roster.json.bak.new").delete()
        File(base, "roster.json.prepared").delete()
        File(base, JOURNAL).delete()
        File(base, JOURNAL + ".tmp").delete()
    }
}
