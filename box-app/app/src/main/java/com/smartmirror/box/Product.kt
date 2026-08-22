package com.smartmirror.box

data class Product(
    val id: Int,
    val name: String,
    val category: String, // "shirt" or "pants"
    val colorKey: String?, // used for category == "shirt"
    val asset: String
)
