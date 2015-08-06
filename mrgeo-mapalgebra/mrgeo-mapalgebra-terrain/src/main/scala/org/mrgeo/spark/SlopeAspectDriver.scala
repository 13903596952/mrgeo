/*
 * Copyright 2009-2015 DigitalGlobe, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and limitations under the License.
 */

package org.mrgeo.spark

import java.awt.image.{DataBuffer, Raster}
import java.io.{Externalizable, ObjectInput, ObjectOutput}
import java.util.Properties
import javax.vecmath.Vector3d

import org.apache.spark.rdd.RDD
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}
import org.mrgeo.data.DataProviderFactory
import org.mrgeo.data.DataProviderFactory.AccessMode
import org.mrgeo.data.raster.{RasterUtils, RasterWritable}
import org.mrgeo.data.tile.TileIdWritable
import org.mrgeo.image.MrsImagePyramidMetadata
import org.mrgeo.spark.job.{JobArguments, MrGeoJob}
import org.mrgeo.utils.{LatLng, SparkUtils, TMSUtils}

object SlopeAspectDriver {
  final val Input = "input"
  final val Output = "output"
  final val Units = "units"
  final val Type = "type"

  final val Slope = "slope"
  final val Aspect = "aspect"

}

class SlopeAspectDriver extends MrGeoJob with Externalizable {

  final val DEG_2_RAD: Double = 0.0174532925
  final val RAD_2_DEG: Double = 57.2957795
  final val TWO_PI:Double = 6.28318530718
  final val THREE_PI_OVER_2:Double = 4.71238898038

  var input:String = null
  var output:String = null
  var units:String = null
  var slope:Boolean = true

  override def registerClasses(): Array[Class[_]] = {
    val classes = Array.newBuilder[Class[_]]

    classes += classOf[TileIdWritable]
    classes += classOf[RasterWritable]
    classes += classOf[TileNeighborhood]

    classes.result()
  }

  override def setup(job: JobArguments, conf: SparkConf): Boolean = {

    conf.set("spark.storage.memoryFraction", "0.2") // set the storage amount lower...
    conf.set("spark.shuffle.memoryFraction", "0.3") // set the shuffle higher

    input = job.getSetting(SlopeAspectDriver.Input)
    output = job.getSetting(SlopeAspectDriver.Output)
    units = job.getSetting(SlopeAspectDriver.Units)

    slope = job.getSetting(SlopeAspectDriver.Type).equals(SlopeAspectDriver.Slope)

    true
  }

  private def calculate(tiles:RDD[(Long, TileNeighborhood)], nodata:Double, zoom:Int, tilesize:Int) = {

    tiles.map(tile => {
      val np = 0
      val zp = 1
      val pp = 2
      val nz = 3
      val zz = 4
      val pz = 5
      val nn = 6
      val zn = 7
      val pn = 8

      val bounds = TMSUtils.tileBounds(TMSUtils.tileid(tile._1, zoom), zoom, tilesize)

      // calculate the great circle distance of the tile (through the middle)
      val midx = bounds.w + ((bounds.e - bounds.w) / 2.0)
      val midy = bounds.s + ((bounds.n - bounds.s) / 2.0)
      val dx = LatLng.calculateGreatCircleDistance(new LatLng(midy, bounds.w), new LatLng(midy, bounds.e)) / tilesize
      val dy = LatLng.calculateGreatCircleDistance(new LatLng(bounds.n, midx), new LatLng(bounds.s, midx)) / tilesize

      val z = Array.ofDim[Double](9)

      val vx = new Vector3d(dx, 0.0, 0.0)
      val vy = new Vector3d(0.0, dy, 0.0)
      val normal = new Vector3d()
      val up = new Vector3d(0, 0, 1.0)  // z (up) direction


      var theta:Double = 0.0

      val elevations = {
        val neighborhood = tile._2

        val rasters = Array.ofDim[Raster](neighborhood.height, neighborhood.width)

        for (y <- 0 until neighborhood.height) {
          for (x <- 0 until neighborhood.width) {
            rasters(y)(x) = RasterWritable.toRaster(neighborhood.neighborAbsolute(x, y))
          }
        }

        //neighborhood.sizeof()

        rasters
      }

      val anchorX = tile._2.anchorX()
      val anchorY = tile._2.anchorY()
      val anchor = elevations(anchorY)(anchorX)

      def isnodata(v:Double, nodata:Double):Boolean = (nodata.isNaN && v.isNaN) || (v == nodata)

      def getElevation(x:Int, y:Int):Double = {

        var offsetX = 0
        var offsetY = 0

        if (x < 0) {
          offsetX = -1
        }
        else if (x >= anchor.getWidth) {
          offsetX = 1
        }

        if (y < 0) {
          offsetY = -1
        }
        else if (y >= anchor.getWidth) {
          offsetY = 1
        }

        var px:Int = x
        if (offsetX < 0) {
          px = anchor.getWidth + x
        }
        else if (offsetX > 0) {
          px = x - anchor.getWidth
        }

        var py:Int = y
        if (offsetY < 0) {
          py = anchor.getHeight + y
        }
        else if (offsetY > 0) {
          py = y - anchor.getHeight
        }

        elevations(anchorY + offsetY)(anchorX + offsetX).getSampleDouble(px, py, 0)
      }

      def calculateNormal(x: Int, y: Int): (Double, Double, Double) = {

        // if the origin pixel is nodata, the normal is nodata
        val origin = anchor.getSampleDouble(x, y, 0)
        if (isnodata(origin, nodata)) {
          return (Double.NaN, Double.NaN, Double.NaN)
        }

        // get the elevations of the 3x3 grid of elevations, if a neighbor is nodata, make the elevation
        // the same as the origin, this makes the slopes a little prettier
        var ndx = 0
        for (dy <- y - 1 to y + 1) {
          for (dx <- x - 1 to x + 1) {
            z(ndx) = getElevation(dx, dy)
            if (isnodata(z(ndx), nodata)) {
              z(ndx) = origin
            }

            ndx += 1
          }
        }

        vx.z = ((z(pp) + z(pz) * 2 + z(pn)) - (z(np) + z(nz) * 2 + z(nn))) / 8.0
        vy.z = ((z(pp) + z(zp) * 2 + z(np)) - (z(pn) + z(zn) * 2 + z(nn))) / 8.0

        normal.cross(vx, vy)
        normal.normalize()

        // we want the normal to always point up.
        normal.z = Math.abs(normal.z)

        (normal.x, normal.y, normal.z)
      }

      def calculateAngle(normal: (Double, Double, Double)): Float = {
        if (normal._1.isNaN) {
          return Float.NaN
        }

        if (slope) {
          theta  = Math.acos(up.dot(new Vector3d(normal._1, normal._2, normal._3)))
        }
        else {  // aspect
          // change from (-Pi to Pi) to (0 to 2Pi), make 0 deg north (+ 3pi/2)
          // convert to clockwise (2pi -)
          theta = TWO_PI - (Math.atan2(normal._2, normal._1) + THREE_PI_OVER_2) % TWO_PI
        }

        units match {
        case "deg"  => (theta * RAD_2_DEG).toFloat
        case "rad" => theta.toFloat
        case "percent" => (Math.tan(theta) * 100.0).toFloat
        case _ => Math.tan(theta).toFloat
        }
      }

      val raster = RasterUtils.createEmptyRaster(anchor.getWidth, anchor.getHeight, 1, DataBuffer.TYPE_FLOAT) // , Float.NaN)

      for (y <- 0 until anchor.getHeight) {
        for (x <- 0 until anchor.getWidth) {
          val normal = calculateNormal(x, y)

          raster.setSample(x, y, 0, calculateAngle(normal))
        }
      }

      (new TileIdWritable(tile._1), RasterWritable.toWritable(raster))
    })
  }


  override def execute(context: SparkContext): Boolean =
  {
    val ip = DataProviderFactory.getMrsImageDataProvider(input, AccessMode.READ, null.asInstanceOf[Properties])

    val metadata: MrsImagePyramidMetadata = ip.getMetadataReader.read
    val zoom = metadata.getMaxZoomLevel
    val pyramid = SparkUtils.loadMrsPyramid(ip, zoom, context)

    val tiles = TileNeighborhood.createNeighborhood(pyramid, -1, -1, 3, 3,
      zoom, metadata.getTilesize, metadata.getDefaultValue(0), context)

    val answer = calculate(tiles, metadata.getDefaultValue(0), zoom, metadata.getTilesize).persist(StorageLevel.MEMORY_AND_DISK)

    val op = DataProviderFactory.getMrsImageDataProvider(output, AccessMode.WRITE, null.asInstanceOf[Properties])

    SparkUtils.saveMrsPyramid(answer, op, output, zoom, metadata.getTilesize, Array[Double](Float.NaN),
      context.hadoopConfiguration, DataBuffer.TYPE_FLOAT, metadata.getBounds, 1, metadata.getProtectionLevel, null)

    answer.unpersist()
    true
  }




  override def teardown(job: JobArguments, conf: SparkConf): Boolean = {
    true
  }

  override def readExternal(in: ObjectInput): Unit = {
    input = in.readUTF()
    output = in.readUTF()
    units = in.readUTF()
    slope = in.readBoolean()
  }

  override def writeExternal(out: ObjectOutput): Unit = {
    out.writeUTF(input)
    out.writeUTF(output)
    out.writeUTF(units)
    out.writeBoolean(slope)
  }
}