package ir.ac.sbu.graph.spark.search.patterns;

import ir.ac.sbu.graph.spark.search.fonl.creator.LocalFonlCreator;
import ir.ac.sbu.graph.spark.search.fonl.local.QFonl;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PatternReaderUtils {

    public static QFonl loadSample(String sampleName) {
        switch (sampleName) {
            case "mySample" : return Samples.mySample();
            case "mySampleEmptyLabel" : return Samples.mySampleEmptyLabel();
            default: return Samples.mySample();
        }
    }

    public static QFonl loadFromFile(String address) throws IOException {
        BufferedReader br = new BufferedReader(new FileReader(address));
        Map<Integer, List<Integer>> neighbors = new HashMap<>();
        Map <Integer, String> labelMap = new HashMap <>();
        boolean readNeighbors = true;
        while (true) {
            String line = br.readLine();
            if (line == null)
                break;
            switch (line) {
                case "#neighbors":
                    readNeighbors = true;
                    break;
                case "#labels":
                    readNeighbors = false;
                    break;
                default:
                    String[] s = line.split("\\s+");
                    int v1 = Integer.parseInt(s[0]);
                    if (readNeighbors) {
                        List <Integer> neighborList = new ArrayList<>();
                        for (int i = 1; i < s.length; i++) {
                            int v2 = Integer.parseInt(s[i]);
                            neighborList.add(v2);
                        }
                        neighbors.put(v1, neighborList);
                    } else {
                        String label = s[1];
                        labelMap.put(v1, label);
                    }
                    break;
            }
        }

        QFonl qFonl = LocalFonlCreator.createQFonl(neighbors, labelMap);
        System.out.println("qFonl" + qFonl);

        return qFonl;
    }
}