package io.github.sds100.keymapper.actions.swipescreen

import android.graphics.Bitmap
import android.graphics.Point
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import io.github.sds100.keymapper.R
import io.github.sds100.keymapper.util.ui.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.math.roundToInt

enum class ScreenshotTouchType {
    START,
    END
}
class SwipePickDisplayCoordinateViewModel(
    resourceProvider: ResourceProvider
) : ViewModel(), ResourceProvider by resourceProvider, PopupViewModel by PopupViewModelImpl() {

    val screenshotTouchTypeStart = ScreenshotTouchType.START
    val screenshotTouchTypeEnd = ScreenshotTouchType.END
    public val screenshotTouchTypeStart = ScreenshotTouchType.START;
    public val screenshotTouchTypeEnd = ScreenshotTouchType.END;
    private val xStart = MutableStateFlow<Int?>(null)
    private val yStart = MutableStateFlow<Int?>(null)
    private val xEnd = MutableStateFlow<Int?>(null)
    private val yEnd = MutableStateFlow<Int?>(null)
    private val fingerCount = MutableStateFlow<Int?>(1)
    private val duration = MutableStateFlow<Int?>(null)

    private val _bitmap = MutableStateFlow<Bitmap?>(null)
    private val _returnResult = MutableSharedFlow<SwipePickCoordinateResult>()
    private val _screenshotTouchType = MutableStateFlow(ScreenshotTouchType.START)

    private val description: MutableStateFlow<String?> = MutableStateFlow(null)

    val xStartString = xStart.map {
        it ?: return@map ""

        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val yStartString = yStart.map {
        it ?: return@map ""

        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val xEndString = xEnd.map {
        it ?: return@map ""

        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val yEndString = yEnd.map {
        it ?: return@map ""

        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val fingerCountString = fingerCount.map {
        it ?: return@map ""

        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val durationString = duration.map {
        it ?: return@map ""

        it.toString()
    }.stateIn(viewModelScope, SharingStarted.Lazily, null)

    val bitmap = _bitmap.asStateFlow()
    val returnResult = _returnResult.asSharedFlow()

    val isSelectStartEndSwitchEnabled:StateFlow<Boolean> = combine(bitmap) {
        bitmap?.value != null
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val isCoordinatesValid: StateFlow<Boolean> = combine(xStart, yStart, xEnd, yEnd) { xStart, yStart, xEnd, yEnd ->
        xStart ?: return@combine false
        yStart ?: return@combine false
        xEnd ?: return@combine false
        yEnd ?: return@combine false

        xStart >= 0 && yStart >= 0 && xEnd >= 0 && yEnd >= 0
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    private val isOptionsValid: StateFlow<Boolean> = combine(fingerCount, duration) { fingerCount, duration ->
        fingerCount ?: return@combine false
        duration ?: return@combine false

        fingerCount > 0 && duration > 0
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    val isDoneButtonEnabled: StateFlow<Boolean> = combine(isCoordinatesValid, isOptionsValid) { isCoordinatesValid, isOptionsValid ->
        isCoordinatesValid && isOptionsValid
    }.stateIn(viewModelScope, SharingStarted.Lazily, false)

    fun selectedScreenshot(newBitmap: Bitmap, displaySize: Point) {
        _screenshotTouchType.value = ScreenshotTouchType.START

        //check whether the height and width of the bitmap match the display size, even when it is rotated.
        if (
            (displaySize.x != newBitmap.width
                && displaySize.y != newBitmap.height) &&

            (displaySize.y != newBitmap.width
                && displaySize.x != newBitmap.height)
        ) {
            viewModelScope.launch {
                val snackBar = PopupUi.SnackBar(
                    message = getString(R.string.toast_incorrect_screenshot_resolution)
                )

                showPopup("incorrect_resolution", snackBar)
            }

            return
        }

        _bitmap.value = newBitmap
    }

    fun setXStart(x: String) {
        this.xStart.value = x.toIntOrNull()
    }

    fun setYStart(y: String) {
        this.yStart.value = y.toIntOrNull()
    }

    fun setXEnd(x: String) {
        this.xEnd.value = x.toIntOrNull()
    }

    fun setYEnd(y: String) {
        this.yEnd.value = y.toIntOrNull()
    }

    fun setFingerCount(fingerCount: String) {
        this.fingerCount.value = fingerCount.toIntOrNull()
    }

    fun setDuration(duration: String) {
        this.duration.value = duration.toIntOrNull()
    }

    fun setStartOrEndCoordinates(isChecked:Boolean, type: ScreenshotTouchType) {
        if (isChecked) this._screenshotTouchType.value = type
    }

    /**
     * [screenshotXRatio] The ratio between the point where the user pressed to the width of the image.
     * [screenshotYRatio] The ratio between the point where the user pressed to the height of the image.
     */
    fun onScreenshotTouch(screenshotXRatio: Float, screenshotYRatio: Float) {
        bitmap.value?.let {

            val displayX = it.width * screenshotXRatio
            val displayY = it.height * screenshotYRatio

            if (_screenshotTouchType.value == ScreenshotTouchType.START) {
                xStart.value = displayX.roundToInt()
                yStart.value = displayY.roundToInt()
            } else {
                xEnd.value = displayX.roundToInt()
                yEnd.value = displayY.roundToInt()
            }
        }
    }

    fun onDoneClick() {
        viewModelScope.launch {
            val xStart = xStart.value ?: return@launch
            val yStart = yStart.value ?: return@launch
            val xEnd = xEnd.value ?: return@launch
            val yEnd = yEnd.value ?: return@launch
            val fingerCount = fingerCount.value ?: return@launch
            val duration = duration.value ?: return@launch

            val description = showPopup(
                "coordinate_description",
                PopupUi.Text(
                    getString(R.string.hint_tap_coordinate_title),
                    allowEmpty = true,
                    text = description.value ?: ""
                )
            ) ?: return@launch

            _returnResult.emit(SwipePickCoordinateResult(xStart, yStart, xEnd, yEnd, fingerCount, duration, description))
        }
    }

    fun loadResult(result: SwipePickCoordinateResult) {
        viewModelScope.launch {
            xStart.value = result.xStart
            yStart.value = result.yStart
            xEnd.value = result.xEnd
            yEnd.value = result.yEnd
            fingerCount.value = result.fingerCount
            duration.value = result.duration
            description.value = result.description
        }
    }

    override fun onCleared() {
        bitmap.value?.recycle()
        _bitmap.value = null

        super.onCleared()
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val resourceProvider: ResourceProvider
    ) : ViewModelProvider.NewInstanceFactory() {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return SwipePickDisplayCoordinateViewModel(resourceProvider) as T
        }
    }
}
