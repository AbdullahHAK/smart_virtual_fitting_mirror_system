package com.smartmirror.box

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

// The local product catalog lives only on the Box, per spec. The Tablet reads
// it over the network (CommandServer's /products route) rather than holding
// its own copy.
class ProductDbHelper(context: Context) : SQLiteOpenHelper(context, "products.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE products (
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                colorKey TEXT,
                asset TEXT NOT NULL
            )
            """.trimIndent()
        )
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS products")
        onCreate(db)
    }

    private fun seed(db: SQLiteDatabase) {
        val rows = listOf(
            Product(1, "Blue Shirt", "shirt", "blue", "shirt_blue.png"),
            Product(2, "Red Shirt", "shirt", "red", "shirt_red.png"),
            Product(3, "Green Shirt", "shirt", "green", "shirt_green.png"),
            Product(4, "Classic Pants", "pants", null, "pants_placeholder_front.png")
        )
        for (p in rows) {
            db.insert("products", null, ContentValues().apply {
                put("id", p.id)
                put("name", p.name)
                put("category", p.category)
                put("colorKey", p.colorKey)
                put("asset", p.asset)
            })
        }
    }

    // Returns the new row's id (SQLite's INTEGER PRIMARY KEY is a rowid alias,
    // so omitting "id" here auto-assigns the next one — no AUTOINCREMENT
    // needed for a tiny local catalog with no strict-monotonicity requirement).
    fun insertProduct(name: String, category: String, colorKey: String?, asset: String): Long =
        writableDatabase.insert("products", null, ContentValues().apply {
            put("name", name)
            put("category", category)
            put("colorKey", colorKey)
            put("asset", asset)
        })

    fun updateProduct(id: Int, name: String, category: String, colorKey: String?, asset: String) {
        writableDatabase.update(
            "products",
            ContentValues().apply {
                put("name", name)
                put("category", category)
                put("colorKey", colorKey)
                put("asset", asset)
            },
            "id = ?",
            arrayOf(id.toString())
        )
    }

    fun deleteProduct(id: Int) {
        writableDatabase.delete("products", "id = ?", arrayOf(id.toString()))
    }

    fun getProduct(id: Int): Product? {
        readableDatabase.query(
            "products", null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return null
            return Product(
                id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                colorKey = cursor.getString(cursor.getColumnIndexOrThrow("colorKey")),
                asset = cursor.getString(cursor.getColumnIndexOrThrow("asset"))
            )
        }
    }

    fun getAllProducts(): List<Product> {
        val result = mutableListOf<Product>()
        readableDatabase.query(
            "products", null, null, null, null, null, "id"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.add(
                    Product(
                        id = cursor.getInt(cursor.getColumnIndexOrThrow("id")),
                        name = cursor.getString(cursor.getColumnIndexOrThrow("name")),
                        category = cursor.getString(cursor.getColumnIndexOrThrow("category")),
                        colorKey = cursor.getString(cursor.getColumnIndexOrThrow("colorKey")),
                        asset = cursor.getString(cursor.getColumnIndexOrThrow("asset"))
                    )
                )
            }
        }
        return result
    }
}
