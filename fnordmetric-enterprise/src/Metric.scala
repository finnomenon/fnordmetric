// FnordMetric Enterprise
//   (c) 2011-2013 Paul Asmuth <paul@paulasmuth.com>
//
// Licensed under the MIT License (the "License"); you may not use this
// file except in compliance with the License. You may obtain a copy of
// the License at: http://opensource.org/licenses/MIT

package com.fnordmetric.enterprise

import scala.collection.mutable.ListBuffer

case class MetricKey(key: String, mode: String, flush_interval: Long)

class Metric(key: MetricKey) {
  val bucket = BucketFactory.new_bucket(key.mode)
  val swap = new SwapFile(key)
  var rbuf = new RingBuffer[(Long, Double)](10)
  var rbuf_seek_pos = 0

  // adds a value to the metric's bucket and tries to flush the bucket
  def sample(value: Double) = this.synchronized {

    bucket.flush_every(key.flush_interval, (
      (time, value) => flush_bucket(time, value) ))

    bucket.sample(value)
  }

  // adds an aggregated value to the in memory ring buffer after it has
  // been flushed from the bucket
  private def flush_bucket(time: Long, value: Double) = {
    if (rbuf.remaining == 0) {

      if (rbuf_seek_pos < 1)
        flush_rbuf

      if (rbuf_seek_pos < 1)
        throw new Exception("flush_rbuf failed")

      rbuf.seek(1)
      rbuf_seek_pos -= 1
    }

    rbuf.push(((time, value)))

    flush_rbuf // FIXPAUL: remove me
  }

  // tries to persist as much data from the in memory ring buffer to disk
  // as possible but doesnt remove it from the buffer yet
  def flush_rbuf = this.synchronized {
    val flush_range = rbuf.size - rbuf_seek_pos

    // FIXPAUL: wrong order!
    for (sample <- rbuf.tail(flush_range))
      swap.put(sample._1, sample._2)

    swap.flush
    rbuf_seek_pos += flush_range
  }


  // returns this metrics value at time0 if a value was recorded at that
  // point in time
  def value_at(time0: Long) : Option[Double] = {
    val values = values_in(time0, 0)

    if (values.size > 0)
      Some(values.first._2)
    else
      None
  }

  // returns all aggregated values for this metric in the specified time
  // range. if time1 is 0 then only the first value at time0 is returned
  // note that time0 > time1! this method is threadsafe
  def values_in(time0: Long, time1: Long) : List[(Long, Double)] = {
    val lst = ListBuffer[(Long, Double)]()
    var found_start : Long = 0

    // FIXPAUL: skip n flush_intervals if time0 is older than now, maybe
    // skip rbuf_search completely

    // FIXPAUL: do this without snapshotting the ringbuffer
    val rbuf_snap = rbuf.tail(rbuf.size)

    var rbuf_last : Long = java.lang.Long.MAX_VALUE
    var rbuf_pos = 0

    // search the ring buffer backwards without synchronization. the basic
    // assumption here is that the system time will only progress forward.
    // if the system time should jump backwards this would race
    while (rbuf_pos >= 0 && rbuf_pos < rbuf_snap.size) {
      val cur = rbuf_snap(rbuf_pos)

      // since this is not synchronized, we need to check if we hit the
      // rbuf wrapping point and exit if so.
      if (cur._1 < rbuf_last)
        rbuf_last = cur._1
      else
        rbuf_pos = -1

      // if we are already beyond time1 we can exit
      if (time1 != 0 && (cur._1 < time1))
        return lst.toList

      // if we are only looking for a single value and already beyond time0
      // plus flush_interval and didnt find a value yet, we can exit
      if (time1 == 0 && (cur._1 < (time0 - key.flush_interval)))
        return lst.toList

      // continues only if we didn't hit the buffer wrap
      if (rbuf_pos >= 0) {

        // check if we found the start of the range yet
        if (cur._1 <= time0 && ((cur._1 >= time1) || time1 == 0)) {

          // collect all matching items
          lst += cur

          if (found_start == 0)
            found_start = cur._1

          // if we are looking only for a single value we can exit now
          if (time1 == 0)
            return lst.toList

        }
      }

      if (rbuf_pos >= 0)
        rbuf_pos += 1
    }

    // exit if we have already seen the whole time range and don't need to
    // search the swapfile anymore
    if (rbuf_last <= time1)
      return lst.toList

    // start searching the swapfile backwards from the last write position
    var swap_chunk = ListBuffer[(Long, Double)]()
    var swap_pos = swap.write_pos

    // we skip at least as many values as we've already seen in the rbuf. but
    // since this is not synchronized we might still load a few samples that
    // we have already seen
    swap_pos -= (rbuf_seek_pos * swap.BLOCK_SIZE)

    while (swap_pos > 0) {
      swap_chunk.clear

      // load the next chunk of samples from the swapfile
      swap_pos = swap.load_chunk(swap_pos, swap_chunk)

      for (cur <- swap_chunk) {

        // skip if we already saw this sample in the rbuf search
        if (cur._1 < rbuf_last) {
          println("LOAD_SWAP", cur)
        }

      }
    }

    lst.toList
  }

}
