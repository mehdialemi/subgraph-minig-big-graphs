package ir.ac.sbu.graph.spark.search.fonl.value;

import java.util.Arrays;

public class FonlValue<T extends Meta> {
    public T meta;
    public int[] fonl;

    @Override
    public String toString() {
        return "Meta: " + meta + ", fonl: " + Arrays.toString(fonl);
    }
}
