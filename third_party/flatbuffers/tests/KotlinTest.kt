/*
 * Copyright 2014 Google Inc. All rights reserved.
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

import MyGame.Example.*
import com.google.flatbuffers.ByteBufferUtil
import com.google.flatbuffers.FlatBufferBuilder
import NamespaceA.*
import NamespaceA.NamespaceB.*
import NamespaceA.NamespaceB.TableInNestedNS
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

import com.google.flatbuffers.Constants.SIZE_PREFIX_LENGTH

@kotlin.ExperimentalUnsignedTypes
class KotlinTest {

  companion object {
    @JvmStatic
    fun main(args: Array<String>) {

        // First, let's test reading a FlatBuffer generated by C++ code:
        // This file was generated from monsterdata_test.json

        val data = RandomAccessFile(File("monsterdata_test.mon"), "r").use {
            val temp = ByteArray(it.length().toInt())
            it.readFully(temp)
            temp
        }

        // Now test it:

        val bb = ByteBuffer.wrap(data)
        TestBuffer(bb)

        // Second, let's create a FlatBuffer from scratch in Java, and test it also.
        // We use an initial size of 1 to exercise the reallocation algorithm,
        // normally a size larger than the typical FlatBuffer you generate would be
        // better for performance.
        val fbb = FlatBufferBuilder(1)

        TestBuilderBasics(fbb, true)
        TestBuilderBasics(fbb, false)

        TestExtendedBuffer(fbb.dataBuffer().asReadOnlyBuffer())

        TestNamespaceNesting()

        TestNestedFlatBuffer()

        TestCreateByteVector()

        TestCreateUninitializedVector()

        TestByteBufferFactory()

        TestSizedInputStream()

        TestVectorOfUnions()

        println("FlatBuffers test: completed successfully")
    }

    fun TestEnums() {
        assert(Color.name(Color.Red.toInt()) == "Red")
        assert(Color.name(Color.Blue.toInt()) == "Blue")
        assert(Any_.name(Any_.NONE.toInt()) == "NONE")
        assert(Any_.name(Any_.Monster.toInt()) == "Monster")
    }

    fun TestBuffer(bb: ByteBuffer) {
        assert(Monster.MonsterBufferHasIdentifier(bb) == true)

        val monster = Monster.getRootAsMonster(bb)

        assert(monster.hp == 80.toShort())
        assert(monster.mana == 150.toShort())  // default

        assert(monster.name == "MyMonster")
        // monster.friendly() // can't access, deprecated

        val pos = monster.pos!!
        assert(pos.x == 1.0f)
        assert(pos.y == 2.0f)
        assert(pos.z == 3.0f)
        assert(pos.test1 == 3.0)
        // issue: int != byte
        assert(pos.test2 == Color.Green)
        val t = pos.test3!!
        assert(t.a == 5.toShort())
        assert(t.b == 6.toByte())

        assert(monster.testType == Any_.Monster)
        val monster2 = Monster()
        assert(monster.test(monster2) != null == true)
        assert(monster2.name == "Fred")

        assert(monster.inventoryLength == 5)
        var invsum = 0u
        for (i in 0 until monster.inventoryLength)
            invsum += monster.inventory(i)
        assert(invsum == 10u)

        // Alternative way of accessing a vector:
        val ibb = monster.inventoryAsByteBuffer
        invsum = 0u
        while (ibb.position() < ibb.limit())
            invsum += ibb.get().toUInt()
        assert(invsum == 10u)


        val test_0 = monster.test4(0)!!
        val test_1 = monster.test4(1)!!
        assert(monster.test4Length == 2)
        assert(test_0.a + test_0.b + test_1.a + test_1.b == 100)

        assert(monster.testarrayofstringLength == 2)
        assert(monster.testarrayofstring(0) == "test1")
        assert(monster.testarrayofstring(1) == "test2")

        assert(monster.testbool == true)
    }

    // this method checks additional fields not present in the binary buffer read from file
    // these new tests are performed on top of the regular tests
    fun TestExtendedBuffer(bb: ByteBuffer) {
        TestBuffer(bb)

        val monster = Monster.getRootAsMonster(bb)

        assert(monster.testhashu32Fnv1 == (1u + Integer.MAX_VALUE.toUInt()))
    }

    fun TestNamespaceNesting() {
        // reference / manipulate these to verify compilation
        val fbb = FlatBufferBuilder(1)

        TableInNestedNS.startTableInNestedNS(fbb)
        TableInNestedNS.addFoo(fbb, 1234)
        val nestedTableOff = TableInNestedNS.endTableInNestedNS(fbb)

        TableInFirstNS.startTableInFirstNS(fbb)
        TableInFirstNS.addFooTable(fbb, nestedTableOff)
    }

    fun TestNestedFlatBuffer() {
        val nestedMonsterName = "NestedMonsterName"
        val nestedMonsterHp: Short = 600
        val nestedMonsterMana: Short = 1024

        var fbb1: FlatBufferBuilder? = FlatBufferBuilder(16)
        val str1 = fbb1!!.createString(nestedMonsterName)
        Monster.startMonster(fbb1)
        Monster.addName(fbb1, str1)
        Monster.addHp(fbb1, nestedMonsterHp)
        Monster.addMana(fbb1, nestedMonsterMana)
        val monster1 = Monster.endMonster(fbb1)
        Monster.finishMonsterBuffer(fbb1, monster1)
        val fbb1Bytes = fbb1.sizedByteArray()
        
        val fbb2 = FlatBufferBuilder(16)
        val str2 = fbb2.createString("My Monster")
        val nestedBuffer = Monster.createTestnestedflatbufferVector(fbb2, fbb1Bytes.asUByteArray())
        Monster.startMonster(fbb2)
        Monster.addName(fbb2, str2)
        Monster.addHp(fbb2, 50.toShort())
        Monster.addMana(fbb2, 32.toShort())
        Monster.addTestnestedflatbuffer(fbb2, nestedBuffer)
        val monster = Monster.endMonster(fbb2)
        Monster.finishMonsterBuffer(fbb2, monster)

        // Now test the data extracted from the nested buffer
        val mons = Monster.getRootAsMonster(fbb2.dataBuffer())
        val nestedMonster = mons.testnestedflatbufferAsMonster!!

        assert(nestedMonsterMana == nestedMonster.mana)
        assert(nestedMonsterHp == nestedMonster.hp)
        assert(nestedMonsterName == nestedMonster.name)
    }

    fun TestCreateByteVector() {
        val fbb = FlatBufferBuilder(16)
        val str = fbb.createString("MyMonster")
        val inventory = byteArrayOf(0, 1, 2, 3, 4)
        val vec = fbb.createByteVector(inventory)
        Monster.startMonster(fbb)
        Monster.addInventory(fbb, vec)
        Monster.addName(fbb, str)
        val monster1 = Monster.endMonster(fbb)
        Monster.finishMonsterBuffer(fbb, monster1)
        val monsterObject = Monster.getRootAsMonster(fbb.dataBuffer())

        assert(monsterObject.inventory(1) == inventory[1].toUByte())
        assert(monsterObject.inventoryLength == inventory.size)
        assert(ByteBuffer.wrap(inventory) == monsterObject.inventoryAsByteBuffer)
    }

    fun TestCreateUninitializedVector() {
        val fbb = FlatBufferBuilder(16)
        val str = fbb.createString("MyMonster")
        val inventory = byteArrayOf(0, 1, 2, 3, 4)
        val bb = fbb.createUnintializedVector(1, inventory.size, 1)
        for (i in inventory) {
            bb.put(i)
        }
        val vec = fbb.endVector()
        Monster.startMonster(fbb)
        Monster.addInventory(fbb, vec)
        Monster.addName(fbb, str)
        val monster1 = Monster.endMonster(fbb)
        Monster.finishMonsterBuffer(fbb, monster1)
        val monsterObject = Monster.getRootAsMonster(fbb.dataBuffer())

        assert(monsterObject.inventory(1) == inventory[1].toUByte())
        assert(monsterObject.inventoryLength == inventory.size)
        assert(ByteBuffer.wrap(inventory) == monsterObject.inventoryAsByteBuffer)
    }

    fun TestByteBufferFactory() {
        class MappedByteBufferFactory : FlatBufferBuilder.ByteBufferFactory() {
            override fun newByteBuffer(capacity: Int): ByteBuffer? {
                var bb: ByteBuffer?
                try {
                    bb = RandomAccessFile("javatest.bin", "rw").channel.map(
                        FileChannel.MapMode.READ_WRITE,
                        0,
                        capacity.toLong()
                    ).order(ByteOrder.LITTLE_ENDIAN)
                } catch (e: Throwable) {
                    println("FlatBuffers test: couldn't map ByteBuffer to a file")
                    bb = null
                }

                return bb
            }
        }

        val fbb = FlatBufferBuilder(1, MappedByteBufferFactory())

        TestBuilderBasics(fbb, false)
    }

    fun TestSizedInputStream() {
        // Test on default FlatBufferBuilder that uses HeapByteBuffer
        val fbb = FlatBufferBuilder(1)

        TestBuilderBasics(fbb, false)

        val `in` = fbb.sizedInputStream()
        val array = fbb.sizedByteArray()
        var count = 0
        var currentVal = 0

        while (currentVal != -1 && count < array.size) {
            try {
                currentVal = `in`.read()
            } catch (e: java.io.IOException) {
                println("FlatBuffers test: couldn't read from InputStream")
                return
            }

            assert(currentVal.toByte() == array[count])
            count++
        }
        assert(count == array.size)
    }

    fun TestBuilderBasics(fbb: FlatBufferBuilder, sizePrefix: Boolean) {
        val names = intArrayOf(fbb.createString("Frodo"), fbb.createString("Barney"), fbb.createString("Wilma"))
        val off = IntArray(3)
        Monster.startMonster(fbb)
        Monster.addName(fbb, names[0])
        off[0] = Monster.endMonster(fbb)
        Monster.startMonster(fbb)
        Monster.addName(fbb, names[1])
        off[1] = Monster.endMonster(fbb)
        Monster.startMonster(fbb)
        Monster.addName(fbb, names[2])
        off[2] = Monster.endMonster(fbb)
        val sortMons = fbb.createSortedVectorOfTables(Monster(), off)

        // We set up the same values as monsterdata.json:

        val str = fbb.createString("MyMonster")

        val inv = Monster.createInventoryVector(fbb, byteArrayOf(0, 1, 2, 3, 4).asUByteArray())

        val fred = fbb.createString("Fred")
        Monster.startMonster(fbb)
        Monster.addName(fbb, fred)
        val mon2 = Monster.endMonster(fbb)

        Monster.startTest4Vector(fbb, 2)
        Test.createTest(fbb, 10.toShort(), 20.toByte())
        Test.createTest(fbb, 30.toShort(), 40.toByte())
        val test4 = fbb.endVector()

        val testArrayOfString =
            Monster.createTestarrayofstringVector(fbb, intArrayOf(fbb.createString("test1"), fbb.createString("test2")))

        Monster.startMonster(fbb)
        Monster.addPos(
            fbb, Vec3.createVec3(
                fbb, 1.0f, 2.0f, 3.0f, 3.0,
                Color.Green, 5.toShort(), 6.toByte()
            )
        )
        Monster.addHp(fbb, 80.toShort())
        Monster.addName(fbb, str)
        Monster.addInventory(fbb, inv)
        Monster.addTestType(fbb, Any_.Monster)
        Monster.addTest(fbb, mon2)
        Monster.addTest4(fbb, test4)
        Monster.addTestarrayofstring(fbb, testArrayOfString)
        Monster.addTestbool(fbb, true)
        Monster.addTesthashu32Fnv1(fbb, UInt.MAX_VALUE + 1u)
        Monster.addTestarrayoftables(fbb, sortMons)
        val mon = Monster.endMonster(fbb)

        if (sizePrefix) {
            Monster.finishSizePrefixedMonsterBuffer(fbb, mon)
        } else {
            Monster.finishMonsterBuffer(fbb, mon)
        }

        // Write the result to a file for debugging purposes:
        // Note that the binaries are not necessarily identical, since the JSON
        // parser may serialize in a slightly different order than the above
        // Java code. They are functionally equivalent though.

        try {
            val filename = "monsterdata_java_wire" + (if (sizePrefix) "_sp" else "") + ".mon"
            val fc = FileOutputStream(filename).channel
            fc.write(fbb.dataBuffer().duplicate())
            fc.close()
        } catch (e: java.io.IOException) {
            println("FlatBuffers test: couldn't write file")
            return
        }

        // Test it:
        var dataBuffer = fbb.dataBuffer()
        if (sizePrefix) {
            assert(
                ByteBufferUtil.getSizePrefix(dataBuffer) + SIZE_PREFIX_LENGTH ==
                dataBuffer.remaining()
            )
            dataBuffer = ByteBufferUtil.removeSizePrefix(dataBuffer)
        }
        TestExtendedBuffer(dataBuffer)

        // Make sure it also works with read only ByteBuffers. This is slower,
        // since creating strings incurs an additional copy
        // (see Table.__string).
        TestExtendedBuffer(dataBuffer.asReadOnlyBuffer())

        TestEnums()

        //Attempt to mutate Monster fields and check whether the buffer has been mutated properly
        // revert to original values after testing
        val monster = Monster.getRootAsMonster(dataBuffer)

        // mana is optional and does not exist in the buffer so the mutation should fail
        // the mana field should retain its default value
        assert(monster.mutateMana(10.toShort()) == false)
        assert(monster.mana == 150.toShort())

        // Accessing a vector of sorted by the key tables
        assert(monster.testarrayoftables(0)!!.name == "Barney")
        assert(monster.testarrayoftables(1)!!.name == "Frodo")
        assert(monster.testarrayoftables(2)!!.name == "Wilma")

        // Example of searching for a table by the key
        assert(monster.testarrayoftablesByKey("Frodo")!!.name == "Frodo")
        assert(monster.testarrayoftablesByKey("Barney")!!.name == "Barney")
        assert(monster.testarrayoftablesByKey("Wilma")!!.name == "Wilma")

        // testType is an existing field and mutating it should succeed
        assert(monster.testType == Any_.Monster)
        assert(monster.mutateTestType(Any_.NONE) == true)
        assert(monster.testType == Any_.NONE)
        assert(monster.mutateTestType(Any_.Monster) == true)
        assert(monster.testType == Any_.Monster)

        //mutate the inventory vector
        assert(monster.mutateInventory(0, 1u) == true)
        assert(monster.mutateInventory(1, 2u) == true)
        assert(monster.mutateInventory(2, 3u) == true)
        assert(monster.mutateInventory(3, 4u) == true)
        assert(monster.mutateInventory(4, 5u) == true)

        for (i in 0 until monster.inventoryLength) {
            assert(monster.inventory(i) == (i.toUByte() + 1u).toUByte())
        }

        //reverse mutation
        assert(monster.mutateInventory(0, 0u) == true)
        assert(monster.mutateInventory(1, 1u) == true)
        assert(monster.mutateInventory(2, 2u) == true)
        assert(monster.mutateInventory(3, 3u) == true)
        assert(monster.mutateInventory(4, 4u) == true)

        // get a struct field and edit one of its fields
        val pos = monster.pos!!
        assert(pos.x == 1.0f)
        pos.mutateX(55.0f)
        assert(pos.x == 55.0f)
        pos.mutateX(1.0f)
        assert(pos.x == 1.0f)
    }

    fun TestVectorOfUnions() {
        val fbb = FlatBufferBuilder()

        val swordAttackDamage = 1

        val characterVector = intArrayOf(Attacker.createAttacker(fbb, swordAttackDamage))

        val characterTypeVector = ubyteArrayOf(Character_.MuLan)

        Movie.finishMovieBuffer(
            fbb,
            Movie.createMovie(
                fbb,
                0u,
                0,
                Movie.createCharactersTypeVector(fbb, characterTypeVector),
                Movie.createCharactersVector(fbb, characterVector)
            )
        )

        val movie = Movie.getRootAsMovie(fbb.dataBuffer())

        assert(movie.charactersTypeLength == characterTypeVector.size)
        assert(movie.charactersLength == characterVector.size)

        assert(movie.charactersType(0) == characterTypeVector[0])

        assert((movie.characters(Attacker(), 0) as Attacker).swordAttackDamage == swordAttackDamage)
    }
}
  }
