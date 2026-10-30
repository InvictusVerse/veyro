package com.veyro.core.protocol.media

/**
 * How one encoded access unit (a video frame) is split into `VIDEO` records (04-PROTOCOL.md 7.5.5).
 *
 * Fragments are at most [Vms1.MAX_FRAGMENT] bytes, so audio records can go out between the
 * fragments of a large keyframe and never wait behind it. Pure; vectors in
 * `protocol/testvectors/media/fragmentation.json`.
 */
public object AccessUnitFragmenter {

    public data class Fragment(val offset: Int, val length: Int, val flags: Int)

    /** The fragments of an access unit of [size] bytes. */
    public fun plan(size: Int, key: Boolean, discontinuity: Boolean, maxFragment: Int = Vms1.MAX_FRAGMENT): List<Fragment> {
        require(size in 1..Vms1.MAX_ACCESS_UNIT) { "access unit size out of range" }
        require(maxFragment in 1..Vms1.MAX_FRAGMENT) { "fragment size out of range" }
        val count = (size + maxFragment - 1) / maxFragment
        return List(count) { i ->
            var flags = 0
            if (i == 0) {
                flags = flags or Vms1.FLAG_START
                if (key) flags = flags or Vms1.FLAG_KEY
                if (discontinuity) flags = flags or Vms1.FLAG_DISCONTINUITY
            }
            if (i == count - 1) flags = flags or Vms1.FLAG_END
            val offset = i * maxFragment
            Fragment(offset, minOf(maxFragment, size - offset), flags)
        }
    }
}
