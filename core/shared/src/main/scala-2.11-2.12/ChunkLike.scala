/*
 * Copyright 2017-2020 John A. De Goes and the ZIO Contributors
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

package zio

import scala.collection.GenTraversableOnce
import scala.collection.IndexedSeqLike
import scala.collection.generic.CanBuildFrom
import scala.collection.immutable.IndexedSeq
import scala.reflect.ClassTag

/**
 * `ChunkLike` represents the capability for a `Chunk` to extend Scala's
 * collection library. Because of changes to Scala's collection library in
 * 2.13, separate versions of this trait are implemented for 2.11 / 2.12 and
 * 2.13 / Dotty. This allows code in `Chunk` to be written without concern for
 * the implementation details of Scala's collection library to the maximum
 * extent possible.
 *
 * Note that `IndexedSeq` is not a referentially transparent interface in that
 * it exposes methods that are partial (e.g. `apply`), allocate mutable state
 * (e.g. `iterator`), or are purely side effecting (e.g. `foreach`). `Chunk`
 * extends `IndexedSeq` to provide interoperability with Scala's collection
 * library but users should avoid these methods whenever possible.
 */
private[zio] trait ChunkLike[+A] extends IndexedSeq[A] with IndexedSeqLike[A, Chunk[A]] { self: Chunk[A] =>

  /**
   * Returns a filtered, mapped subset of the elements of this chunk.
   */
  override final def collect[B, That](pf: PartialFunction[A, B])(implicit bf: CanBuildFrom[Chunk[A], B, That]): That =
    bf match {
      case _: ChunkCanBuildFrom[_] => collectChunk(pf)
      case _                       => super.collect(pf)
    }

  /**
   * Returns the concatenation of mapping every element into a new chunk using
   * the specified function.
   */
  override final def flatMap[B, That](
    f: A => GenTraversableOnce[B]
  )(implicit bf: CanBuildFrom[Chunk[A], B, That]): That =
    bf match {
      case _: ChunkCanBuildFrom[_] => flatMapChunk(f)
      case _                       => super.flatMap(f)
    }

  /**
   * Returns the first index for which the given predicate is satisfied.
   */
  override final def indexWhere(f: A => Boolean): Int =
    indexWhere(f, 0)

  /**
   * Returns a chunk with the elements mapped by the specified function.
   */
  override final def map[B, That](f: A => B)(implicit bf: CanBuildFrom[Chunk[A], B, That]): That =
    bf match {
      case _: ChunkCanBuildFrom[_] => mapChunk(f)
      case _                       => super.map(f)
    }

  /**
   * Generates a readable string representation of this chunk using the
   * specified start, separator, and end strings.
   */
  override final def mkString(start: String, sep: String, end: String): String = {
    val builder = new scala.collection.mutable.StringBuilder()

    builder.append(start)

    var i   = 0
    val len = self.length

    while (i < len) {
      if (i != 0) builder.append(sep)
      builder.append(self(i).toString)
      i += 1
    }

    builder.append(end)

    builder.toString
  }

  /**
   * Generates a readable string representation of this chunk using the
   * specified separator string.
   */
  override final def mkString(sep: String): String =
    mkString("", sep, "")

  /**
   * Generates a readable string representation of this chunk.
   */
  override final def mkString: String =
    mkString("")

  /**
   * Determines if the chunk is not empty.
   */
  override final def nonEmpty: Boolean =
    length > 0

  /**
   * The number of elements in the chunk.
   */
  override final def size: Int =
    length

  /**
   * The implementation of `flatMap` for `Chunk`.
   */
  protected final def flatMapChunk[B, That](f: A => GenTraversableOnce[B]): Chunk[B] = {
    val len                    = self.length
    var chunks: List[Chunk[B]] = Nil

    var i               = 0
    var total           = 0
    var B0: ClassTag[B] = null.asInstanceOf[ClassTag[B]]
    while (i < len) {
      val chunk = ChunkLike.fromGenTraversableOnce(f(self(i)))

      if (chunk.length > 0) {
        if (B0 == null)
          B0 = Chunk.classTagOf(chunk)

        chunks ::= chunk
        total += chunk.length
      }

      i += 1
    }

    if (B0 == null) Chunk.empty
    else {
      implicit val B: ClassTag[B] = B0

      val dest: Array[B] = Array.ofDim(total)

      val it = chunks.iterator
      var n  = total
      while (it.hasNext) {
        val chunk = it.next
        n -= chunk.length
        chunk.toArray(n, dest)
      }

      Chunk.fromArray(dest)
    }
  }

  /**
   * Zips this chunk with the index of every element.
   */
  final def zipWithIndex: Chunk[(A, Int)] =
    zipWithIndexFrom(0)

  /**
   * Constructs a new `ChunkBuilder`. This operation allocates mutable state
   * and is not referentially transparent. It is provided for compatibility
   * with Scala's collection library and should not be used for other purposes.
   */
  override protected[this] def newBuilder: ChunkBuilder[A] =
    ChunkBuilder.make()
}

object ChunkLike {

  /**
   * Provides implicit evidence that that a collection of type `Chunk[A]` can
   * be build from elements of type `A`.
   */
  implicit def chunkCanBuildFrom[A](implicit bf: ChunkCanBuildFrom[A]): ChunkCanBuildFrom[A] =
    bf

  /**
   * Constructs a `Chunk` from a collection that may potentially only be
   * traversed once.
   */
  private def fromGenTraversableOnce[A](as: GenTraversableOnce[A]): Chunk[A] =
    as match {
      case iterable: Iterable[A] => Chunk.fromIterable(iterable)
      case iterableOnce =>
        val chunkBuilder = ChunkBuilder.make[A]()
        iterableOnce.foreach(chunkBuilder += _)
        chunkBuilder.result()
    }
}
