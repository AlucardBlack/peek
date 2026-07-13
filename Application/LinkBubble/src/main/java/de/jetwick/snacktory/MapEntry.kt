/**
 * Copyright (C) 2010 Peter Karich <>
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package de.jetwick.snacktory

import java.io.Serializable

/**
 * Simple impl of Map.Entry. So that we can have ordered maps.
 *
 * @author Peter Karich, peat_hal 'at' users 'dot' sourceforge 'dot' net
 */
class MapEntry<K, V>(override val key: K, initialValue: V) : MutableMap.MutableEntry<K, V>, Serializable {

    private var _value: V = initialValue

    override val value: V
        get() = _value

    override fun setValue(newValue: V): V {
        _value = newValue
        return newValue
    }

    override fun toString(): String {
        return "$key, $value"
    }

    override fun equals(other: Any?): Boolean {
        if (other == null)
            return false
        if (javaClass != other.javaClass)
            return false
        @Suppress("UNCHECKED_CAST")
        val otherEntry = other as MapEntry<K, V>
        if (this.key != otherEntry.key && (this.key == null || this.key != otherEntry.key))
            return false
        if (this.value != otherEntry.value && (this.value == null || this.value != otherEntry.value))
            return false
        return true
    }

    override fun hashCode(): Int {
        var hash = 7
        hash = 19 * hash + (if (this.key != null) this.key.hashCode() else 0)
        hash = 19 * hash + (if (this.value != null) this.value.hashCode() else 0)
        return hash
    }

    companion object {
        private const val serialVersionUID = 1L
    }
}
