package com.saitamagrs.flashnow.viewmodels

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class HomeViewModel : ViewModel() {
    
    private val _sosActive = MutableLiveData<Boolean>(false)
    val sosActive: LiveData<Boolean> = _sosActive
    
    private val _strobeActive = MutableLiveData<Boolean>(false)
    val strobeActive: LiveData<Boolean> = _strobeActive
    
    fun setSosActive(active: Boolean) {
        _sosActive.value = active
    }
    
    fun setStrobeActive(active: Boolean) {
        _strobeActive.value = active
    }
}