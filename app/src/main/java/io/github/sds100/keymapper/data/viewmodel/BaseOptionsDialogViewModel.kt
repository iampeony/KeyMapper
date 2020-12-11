@file:Suppress("UNCHECKED_CAST")

package io.github.sds100.keymapper.data.viewmodel

import android.os.Bundle
import androidx.lifecycle.LiveData
import com.hadilq.liveevent.LiveEvent
import io.github.sds100.keymapper.data.model.options.BaseOptions

/**
 * Created by sds100 on 28/11/20.
 */

abstract class BaseOptionsDialogViewModel<O : BaseOptions<*>> : BaseOptionsViewModel<O>() {

    private val _onSaveEvent = LiveEvent<BaseOptions<*>>()
    val onSaveEvent: LiveData<BaseOptions<*>> = _onSaveEvent

    fun save() {
        _onSaveEvent.value = options.value!!
    }

    fun saveState(outState: Bundle) {
        outState.putParcelable(stateKey, options.value)
    }

    @Suppress("UNCHECKED_CAST")
    fun restoreState(state: Bundle) {
        state.getParcelable<O>(stateKey)?.let {
            setOptions(it)
        }
    }

    abstract val stateKey: String
}