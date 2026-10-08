package com.example.novelreader

import androidx.compose.runtime.*

class BookDownloadState(val id: String) {
    var downloadStatus by mutableStateOf("")
    var downloading by mutableStateOf(false)
    var downloadingBook by mutableStateOf<Book?>(null)
    var downloadTitle by mutableStateOf("")
    var cachedChapters by mutableStateOf(0)
    var totalChapters by mutableStateOf(0)
    var resumableId by mutableStateOf("")
}
