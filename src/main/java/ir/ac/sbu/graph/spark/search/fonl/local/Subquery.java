package ir.ac.sbu.graph.spark.search.fonl.local;

import ir.ac.sbu.graph.types.Edge;
import it.unimi.dsi.fastutil.ints.Int2IntMap;

import java.util.HashSet;
import java.util.Set;

public class Subquery {
    public int vertex;
    public String label;
    public int degree;
    public int[] fonl;
    public String[] labels;
    public int[] degrees;
    public int[] tcArray;
    public int tc;
    public Set<Edge> edges = new HashSet <>();
    public Int2IntMap v2i;

    public void addEdge(Edge edge) {
        if (edges == null)
            edges = new HashSet <>();
        edges.add(edge);
    }
}