/*
 * Copyright @ 2018 - present 8x8, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jitsi.nlj.util

import io.kotlintest.shouldBe
import io.kotlintest.specs.ShouldSpec

internal class ArrayCacheTest : ShouldSpec() {

    data class Dummy(val index: Int)

    private val arrayCache = ArrayCache<Dummy>(10, false, { Dummy(it.index) })

    init {
        val data1 = Dummy(100)
        val dataOlder = Dummy(98)
        val dataNewer = Dummy(101)
        val dataTooOld = Dummy(77)

        "adding and retrieving items " {
            arrayCache.insertItem(data1, data1.index) shouldBe true
            arrayCache.getContainer(data1.index)!!.item shouldBe data1
        }

        "adding and retrieving older items " {
            arrayCache.insertItem(dataOlder, dataOlder.index) shouldBe true
            arrayCache.getContainer(dataOlder.index)!!.item shouldBe dataOlder
        }

        "adding an item with an index which is too old, and retrieving existing data " {
            arrayCache.insertItem(dataTooOld, dataTooOld.index) shouldBe false
            arrayCache.getContainer(data1.index)!!.item shouldBe data1
            arrayCache.getContainer(dataOlder.index)!!.item shouldBe dataOlder
        }

        "replacing the data at the lates index" {
            val otherData = Dummy(11111)
            arrayCache.insertItem(otherData, 100) shouldBe true
            arrayCache.getContainer(100)!!.item shouldBe otherData
        }

        "replacing the data at an older index" {
            val otherData = Dummy(22222)
            arrayCache.insertItem(otherData, 98) shouldBe true
            arrayCache.getContainer(98)!!.item shouldBe otherData
        }

        "adding and retrieving more data" {
            arrayCache.insertItem(dataNewer, dataNewer.index) shouldBe true
            arrayCache.getContainer(dataNewer.index)!!.item shouldBe dataNewer

            for (i in 150..200) {
                arrayCache.insertItem(Dummy(i), i) shouldBe true
            }
            arrayCache.getContainer(199)!!.item shouldBe Dummy(199)
        }
        "retrieving rewritten data" {
            arrayCache.getContainer(data1.index) shouldBe null
        }
        "retrieving data with a newer index" {
            arrayCache.getContainer(1000) shouldBe null
        }
        "keeping track of statistics " {
            arrayCache.numInserts shouldBe 56
            arrayCache.numOldInserts shouldBe 1
            arrayCache.numHits shouldBe 8
            arrayCache.numMisses shouldBe 2
        }
    }
}