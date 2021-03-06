package ir.ac.sbu.graph.clusteringco

import ir.ac.sbu.graph.utils.{OutUtils, GraphUtils}
import org.apache.spark.graphx._
import org.apache.spark.storage.StorageLevel
import org.apache.spark.{SparkConf, SparkContext}

import scala.collection.mutable.ListBuffer

/**
  * Find ktruss subgraph using pregel like procedure.
  * This implemented pregel like system
  */
object PregelTC {

    case class OneNeighborMsg(vId: Long, fonlValue: Array[Long])

    case class NeighborMessage(list: ListBuffer[OneNeighborMsg])

    def main(args: Array[String]): Unit = {
        var inputPath = "/home/mehdi/ir.ac.sbu.graph-data/com-amazon.ungraph.txt"
        if (args != null && args.length > 0)
            inputPath = args(0);

        var partition = 2
        if (args != null && args.length > 1)
            partition = Integer.parseInt(args(1));
        val conf = new SparkConf()
        if (args == null || args.length == 0)
            conf.setMaster("local[2]")
        GraphUtils.setAppName(conf, "Pregel-TC", partition, inputPath);

        val sc = new SparkContext(conf)

        // Load int ir.ac.sbu.graph which is as a list of edges
        val inputGraph = GraphLoader.edgeListFile(sc, inputPath, numEdgePartitions = partition)

        // Change direction from lower degree node to a higher node
        // First find degree of each node
        // Second find correct edge direction
        // Third getOrCreate a new ir.ac.sbu.graph with new edges and previous vertices

        // Set degree of each vertex in the property.
        val graphVD = inputGraph.outerJoinVertices(inputGraph.degrees)((vid, vertex, degree) => degree)

        // Find new edges with correct direction. A direction from a lower degree node to a higher degree node.
        val newEdges = graphVD.triplets.map { et =>
            if (et.srcAttr.getOrElse(0) <= et.dstAttr.getOrElse(0))
                Edge(et.srcId, et.dstId, true)
            else
                Edge(et.dstId, et.srcId, true)
        }

        val empty = sc.makeRDD(Array[(Long, Boolean)]())

        // Create ir.ac.sbu.graph with edge direction from lower degree to higher degree node and edge attribute.
        var graph = Graph(empty, newEdges)

        // =======================================================
        // phase 1: Send message about completing the third edges.
        // =======================================================

        // Find outlink fonlValue ids
        val neighborIds = graph.collectNeighborIds(EdgeDirection.Out)

        // Update each nodes with its outlink fonlValue' id.
        val graphWithOutlinks = graph.outerJoinVertices(neighborIds)((vid, _, nId) => nId.getOrElse(Array[Long]()))
        graphWithOutlinks.vertices.repartition(numPartitions = partition).persist(StorageLevel.DISK_ONLY)

        // Send neighborIds of a node to all other its fonlValue.
        val message = graphWithOutlinks.aggregateMessages(
            (ctx: EdgeContext[Array[Long], Boolean, List[(Long, Array[Long])]]) => {
                val msg = List((ctx.srcId, ctx.srcAttr))
                ctx.sendToDst(msg)
            }, (msg1: List[(Long, Array[Long])], msg2: List[(Long, Array[Long])]) => msg1 ::: msg2)

        // =======================================================
        // phase 2: Find triangles
        // =======================================================
        // At first each node receive messages from its neighbor telling their fonlValue' id.
        // Then check that if receiving neighborIds have a common with its fonlValue.
        // If there was any common fonlValue then it report back telling the sender the completing nodes to make
        // a triangle through it.
        val tCount = graphWithOutlinks.vertices.join(message).flatMap { case (vid, (n, msg)) =>
            msg.map(ids => (n.intersect(ids._2))).filter(_.size > 0)
        }.map(t => t.size).reduce((a, sign) => a + sign)

        OutUtils.printOutputTC(tCount)

        sc.stop()
    }
}
