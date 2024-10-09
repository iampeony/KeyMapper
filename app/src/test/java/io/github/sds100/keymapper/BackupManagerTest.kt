package io.github.sds100.keymapper

import com.github.salomonbrys.kotson.get
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.github.sds100.keymapper.actions.sound.SoundFileInfo
import io.github.sds100.keymapper.actions.sound.SoundsManager
import io.github.sds100.keymapper.backup.BackupManagerImpl
import io.github.sds100.keymapper.data.db.AppDatabase
import io.github.sds100.keymapper.data.entities.ActionEntity
import io.github.sds100.keymapper.data.entities.Extra
import io.github.sds100.keymapper.data.entities.FingerprintMapEntity
import io.github.sds100.keymapper.data.entities.KeyMapEntity
import io.github.sds100.keymapper.data.repositories.FakePreferenceRepository
import io.github.sds100.keymapper.data.repositories.PreferenceRepository
import io.github.sds100.keymapper.mappings.fingerprintmaps.FingerprintMapRepository
import io.github.sds100.keymapper.mappings.keymaps.KeyMapRepository
import io.github.sds100.keymapper.system.files.FakeFileAdapter
import io.github.sds100.keymapper.util.Error
import io.github.sds100.keymapper.util.State
import io.github.sds100.keymapper.util.Success
import io.github.sds100.keymapper.util.UuidGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.core.IsInstanceOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import org.mockito.kotlin.any
import org.mockito.kotlin.anyVararg
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import timber.log.Timber
import java.io.File

/**
 * Created by sds100 on 19/04/2021.
 */

@Suppress("BlockingMethodInNonBlockingContext")
@ExperimentalCoroutinesApi
@RunWith(MockitoJUnitRunner::class)
class BackupManagerTest {

    @get:Rule
    var temporaryFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private val dispatcherProvider = TestDispatcherProvider(testDispatcher)

    private lateinit var backupManager: BackupManagerImpl
    private lateinit var fakeFileAdapter: FakeFileAdapter
    private lateinit var fakePreferenceRepository: PreferenceRepository
    private lateinit var mockKeyMapRepository: KeyMapRepository
    private lateinit var mockFingerprintMapRepository: FingerprintMapRepository
    private lateinit var mockSoundsManager: SoundsManager
    private lateinit var mockUuidGenerator: UuidGenerator

    private lateinit var parser: JsonParser
    private lateinit var gson: Gson

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)

        Timber.plant(TestLoggingTree())

        fakePreferenceRepository = FakePreferenceRepository()

        mockKeyMapRepository = mock {
            on { requestBackup }.then { MutableSharedFlow<List<KeyMapEntity>>() }
        }

        mockFingerprintMapRepository = mock {
            on { requestBackup }.then { MutableSharedFlow<List<KeyMapEntity>>() }
        }

        fakeFileAdapter = FakeFileAdapter(temporaryFolder)

        mockSoundsManager = mock {
            on { soundFiles }.then { MutableStateFlow(emptyList<SoundFileInfo>()) }
        }

        mockUuidGenerator = mock()

        backupManager = BackupManagerImpl(
            testScope,
            fileAdapter = fakeFileAdapter,
            keyMapRepository = mockKeyMapRepository,
            preferenceRepository = fakePreferenceRepository,
            fingerprintMapRepository = mockFingerprintMapRepository,
            throwExceptions = true,
            dispatchers = dispatcherProvider,
            soundsManager = mockSoundsManager,
            uuidGenerator = mockUuidGenerator,
        )

        parser = JsonParser()
        gson = Gson()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * #745
     */
    @Test
    fun `Don't allow back ups from a newer version of key mapper`() =
        runTest(testDispatcher) {
            advanceUntilIdle()

            // GIVEN
            val dataJsonFile = "restore-app-version-too-big.zip/data.json"
            val zipFile = fakeFileAdapter.getPrivateFile("backup.zip")

            copyFileToPrivateFolder(dataJsonFile, destination = "backup.zip/data.json")

            // WHEN
            val result = backupManager.restoreMappings(zipFile.uri)
            advanceUntilIdle()

            // THEN
            assertThat(result, `is`(Error.BackupVersionTooNew))
        }

    /**
     * #745
     */
    @Test
    fun `Allow back ups from a back up without a key mapper version in it`() =
        runTest(testDispatcher) {
            // GIVEN
            whenever(mockKeyMapRepository.keyMapList).then {
                MutableStateFlow(State.Data(emptyList<KeyMapEntity>()))
            }

            whenever(mockFingerprintMapRepository.fingerprintMapList).then {
                MutableStateFlow(State.Data(emptyList<FingerprintMapEntity>()))
            }

            val dataJsonFile = "restore-no-app-version.zip/data.json"
            val zipFile = fakeFileAdapter.getPrivateFile("backup.zip")

            copyFileToPrivateFolder(dataJsonFile, destination = "backup.zip/data.json")

            // WHEN

            val result = backupManager.restoreMappings(zipFile.uri)

            // THEN
            assertThat(result, `is`(Success(Unit)))
        }

    @Test
    fun `don't crash if back up does not contain sounds folder`() = runTest(testDispatcher) {
        // GIVEN
        whenever(mockKeyMapRepository.keyMapList).then {
            MutableStateFlow(State.Data(emptyList<KeyMapEntity>()))
        }

        whenever(mockFingerprintMapRepository.fingerprintMapList).then {
            MutableStateFlow(State.Data(emptyList<FingerprintMapEntity>()))
        }

        val dataJsonFile = "restore-no-sounds-folder.zip/data.json"
        val zipFile = fakeFileAdapter.getPrivateFile("backup.zip")

        copyFileToPrivateFolder(dataJsonFile, destination = "backup.zip/data.json")

        // WHEN
        val result = backupManager.restoreMappings(zipFile.uri)

        // THEN
        assertThat(result, `is`(Success(Unit)))
    }

    @Test
    fun `successfully restore zip folder with data json and sound files`() =
        runTest(testDispatcher) {
            // GIVEN
            val dataJsonFile = "restore-all.zip/data.json"
            val soundFile = "restore-all.zip/sounds/sound.ogg"
            val zipFile = fakeFileAdapter.getPrivateFile("backup.zip")

            copyFileToPrivateFolder(dataJsonFile, destination = "backup.zip/data.json")
            copyFileToPrivateFolder(soundFile, destination = "backup.zip/sounds/sound.ogg")

            // WHEN
            val result = backupManager.restoreMappings(zipFile.uri)

            // THEN
            assertThat(result, `is`(Success(Unit)))

            verify(mockKeyMapRepository, times(1)).insert(any(), any())
            verify(mockFingerprintMapRepository, times(1)).update(any(), any(), any(), any())
            verify(mockSoundsManager, times(1)).restoreSound(any())
        }

    /**
     * #652. always back up sound files.
     */
    @Test
    fun `backup sound file even if there is not a key map with a sound action`() =
        runTest(testDispatcher) {
            // GIVEN
            val backupDirUuid = "backup_uid"
            val soundFileName = "sound.ogg"
            val soundFileUid = "sound_file_uid"

            val soundFile = fakeFileAdapter.getPrivateFile("sounds/sound.ogg")
            soundFile.createFile()

            whenever(mockKeyMapRepository.keyMapList).then {
                MutableStateFlow(State.Data(emptyList<KeyMapEntity>()))
            }

            whenever(mockFingerprintMapRepository.fingerprintMapList).then {
                MutableStateFlow(State.Data(emptyList<FingerprintMapEntity>()))
            }

            whenever(mockUuidGenerator.random()).then {
                backupDirUuid
            }

            whenever(mockSoundsManager.soundFiles).then {
                MutableStateFlow(listOf(SoundFileInfo(uid = soundFileUid, name = soundFileName)))
            }

            whenever(mockSoundsManager.getSound(any())).then {
                Success(fakeFileAdapter.getPrivateFile("sounds/$soundFileName"))
            }

            // WHEN
            val backupZip = File(temporaryFolder.root, "backup.zip")
            backupZip.mkdirs()

            val result = backupManager.backupMappings(uri = backupZip.path)

            // THEN
            assertThat(result, `is`(Success(backupZip.path)))

            // only 2 files have been backed up
            assertThat(backupZip.listFiles()?.size, `is`(2))

            // only 1 sound file has been backed up
            val soundsDir = File(backupZip, "sounds")
            assertThat(soundsDir.listFiles()?.size, `is`(1))
            assert(File(soundsDir, "sound.ogg").exists())
        }

    @Test
    fun `backup sound file if there is a key map with a sound action`() =
        runTest(testDispatcher) {
            // GIVEN
            val backupDirUuid = "backup_uuid"
            val soundFileUid = "uid"
            val soundFileName = "sound.ogg"

            val action = ActionEntity(
                type = ActionEntity.Type.SOUND,
                data = soundFileUid,
                extra = Extra(ActionEntity.EXTRA_SOUND_FILE_DESCRIPTION, "sound_description"),
            )

            val keyMapList = listOf(KeyMapEntity(id = 0, actionList = listOf(action)))

            whenever(mockKeyMapRepository.keyMapList).then {
                MutableStateFlow(State.Data(emptyList<KeyMapEntity>()))
            }

            whenever(mockUuidGenerator.random()).then {
                backupDirUuid
            }

            whenever(mockSoundsManager.soundFiles).then {
                MutableStateFlow(listOf(SoundFileInfo(uid = soundFileUid, name = soundFileName)))
            }

            whenever(mockSoundsManager.getSound(any())).then {
                Success(fakeFileAdapter.getPrivateFile("sounds/sound.ogg"))
            }

            val soundFile = fakeFileAdapter.getPrivateFile("sounds/sound.ogg")
            soundFile.createFile()

            // WHEN
            val backupZip = File(temporaryFolder.root, "backup.zip")
            backupZip.mkdirs()

            val result = backupManager.backupKeyMaps(backupZip.path, keyMapList.map { it.uid })

            // THEN

            assertThat(result, `is`(Success(backupZip.path)))

            // only 2 files have been backed up
            assertThat(backupZip.listFiles()?.size, `is`(2))

            // only 1 sound file has been backed up
            val soundsDir = File(backupZip, "sounds")

            assertThat(soundsDir.listFiles()?.size, `is`(1))
            assert(File(soundsDir, "sound.ogg").exists())
        }

    @Test
    fun `restore legacy backup with device info, success`() = runTest(testDispatcher) {
        // GIVEN
        val fileName = "legacy-backup-test-data.json"

        // WHEN
        val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

        // THEN
        assertThat(result, `is`(Success(Unit)))
        verify(mockKeyMapRepository, times(1)).insert(any(), any())
        verify(mockFingerprintMapRepository, times(1)).update(any(), any(), any(), any())
    }

    @Test
    fun `restore keymaps with no db version, assume version is 9 and don't show error message`() =
        runTest(testDispatcher) {
            val fileName = "restore-keymaps-no-db-version.json"

            val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

            assertThat(result, `is`(Success(Unit)))
            verify(mockKeyMapRepository, times(1)).insert(any(), any())
        }

    @Test
    fun `restore a single legacy fingerprint map, only restore a single fingerprint map and a success message`() =
        runTest(testDispatcher) {
            val fileName = "restore-legacy-single-fingerprint-map.json"

            val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

            assertThat(result, `is`(Success(Unit)))
            verify(mockFingerprintMapRepository, times(1)).update(any())
        }

    @Test
    fun `restore all legacy fingerprint maps, all fingerprint maps should be restored and a success message`() =
        runTest(testDispatcher) {
            val fileName = "restore-all-legacy-fingerprint-maps.json"

            val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

            assertThat(result, `is`(Success(Unit)))
            verify(mockFingerprintMapRepository, times(1)).update(any(), any(), any(), any())
        }

    @Test
    fun `restore many key maps and device info, all key maps and device info should be restored and a success message`() =
        runTest(testDispatcher) {
            val fileName = "restore-many-keymaps.json"

            val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

            assertThat(result, `is`(Success(Unit)))
            verify(mockKeyMapRepository, times(1)).insert(any(), any(), any(), any())
        }

    @Test
    fun `restore with key map db version greater than allowed version, send incompatible backup event`() =
        runTest(testDispatcher) {
            val fileName = "restore-keymap-db-version-too-big.json"

            val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

            assertThat(result, `is`(Error.BackupVersionTooNew))
            verify(mockKeyMapRepository, never()).insert(anyVararg())
        }

    @Test
    fun `restore with legacy fingerprint gesture map db version greater than allowed version, send incompatible backup event`() =
        runTest(testDispatcher) {
            val fileName = "restore-legacy-fingerprint-map-version-too-big.json"

            val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

            assertThat(result, `is`(Error.BackupVersionTooNew))
            verify(mockFingerprintMapRepository, never()).update(anyVararg())
        }

    @Test
    fun `restore empty file, show empty json error message`() = runTest(testDispatcher) {
        val fileName = "empty.json"

        val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

        assertThat(result, `is`(Error.EmptyJson))
    }

    @Test
    fun `restore corrupt file, show corrupt json message`() = runTest(testDispatcher) {
        val fileName = "corrupt.json"

        val result = backupManager.restoreMappings(copyFileToPrivateFolder(fileName))

        assertThat(result, IsInstanceOf(Error.CorruptJsonFile::class.java))
    }

    @Test
    fun `backup all fingerprint maps, return list of fingerprint maps and app database version`() =
        runTest(testDispatcher) {
            // GIVEN
            val backupDirUuid = "backup_uuid"

            val fingerprintMapsToBackup = listOf(
                FingerprintMapEntity(id = FingerprintMapEntity.ID_SWIPE_DOWN),
                FingerprintMapEntity(id = FingerprintMapEntity.ID_SWIPE_UP),
                FingerprintMapEntity(id = FingerprintMapEntity.ID_SWIPE_LEFT),
                FingerprintMapEntity(id = FingerprintMapEntity.ID_SWIPE_RIGHT),
            )

            whenever(mockFingerprintMapRepository.fingerprintMapList).then {
                flow { emit(State.Data(fingerprintMapsToBackup)) }
            }

            whenever(mockUuidGenerator.random()).then {
                backupDirUuid
            }

            val backupZip = File(temporaryFolder.root, "backup.zip")
            backupZip.mkdirs()

            // WHEN
            val result = backupManager.backupFingerprintMaps(backupZip.path)
            // THEN

            assertThat(result, `is`(Success(backupZip.path)))

            // only 1 file has been backed up
            assertThat(backupZip.listFiles()?.size, `is`(1))

            val dataJson = File(backupZip, "data.json")

            val json = dataJson.inputStream().bufferedReader().use { it.readText() }
            val rootElement = parser.parse(json)

            assertThat(
                gson.toJson(rootElement["fingerprint_map_list"]),
                `is`(gson.toJson(fingerprintMapsToBackup)),
            )

            assertThat(
                rootElement["keymap_db_version"].asInt,
                `is`(AppDatabase.DATABASE_VERSION),
            )
        }

    @Test
    fun `backup key maps, return list of default key maps, keymap db version should be current database version`() =
        runTest(testDispatcher) {
            // GIVEN
            val backupDirUuid = "backup_uuid"

            whenever(mockUuidGenerator.random()).then {
                backupDirUuid
            }

            val keyMapList = listOf(KeyMapEntity(0), KeyMapEntity(1))

            whenever(mockKeyMapRepository.keyMapList).then { MutableStateFlow(State.Data(keyMapList)) }

            val backupZip = File(temporaryFolder.root, "backup.zip")
            backupZip.mkdirs()

            // WHEN
            val result = backupManager.backupKeyMaps(backupZip.path, keyMapList.map { it.uid })

            // THEN
            assertThat(result, `is`(Success(backupZip.path)))

            // only 1 file has been backed up
            assertThat(backupZip.listFiles()?.size, `is`(1))

            val dataJson = File(backupZip, "data.json")
            val json = dataJson.inputStream().bufferedReader().use { it.readText() }
            val rootElement = parser.parse(json)

            // the key maps have been backed up
            assertThat(
                gson.toJson(rootElement["keymap_list"]),
                `is`(gson.toJson(keyMapList)),
            )

            // the database version has been backed up
            assertThat(
                rootElement["keymap_db_version"].asInt,
                `is`(AppDatabase.DATABASE_VERSION),
            )
        }

    /**
     * @return a path to the copied file
     */
    private fun copyFileToPrivateFolder(fileName: String, destination: String = fileName): String {
        val inputStream =
            this.javaClass.classLoader!!.getResourceAsStream("backup-manager-test/$fileName")

        inputStream.use { input ->
            val file = fakeFileAdapter.getPrivateFile(destination)
            file.createFile()

            file.outputStream().use { output ->
                input.copyTo(output!!)
            }

            return file.path
        }
    }
}
