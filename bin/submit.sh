#!/bin/bash

if [ -z ${SPARK_HOME+X} ]; then
    SPARK_HOME="/home/$USER/spark-2"
fi

jar_path="$PWD/bin/graph-processing.jar"
master="malemi-2"
total_cores=110
persistOnDisk="false"
repartition="true"
userSort="true"

task=$1
case $task in
    "GCC_Hob")  main_class="graph.clusteringco.FonlHobGCC"
    ;;
	"GCC_Deg") 	main_class="graph.clusteringco.FonlDegGCC"
	;;
	"LCC_Deg")	main_class="graph.clusteringco.FonlDegLCC"
	;;
	"TC_Deg")   main_class="graph.clusteringco.FonlDegTC"
	;;
	"GCC_Id")  main_class="graph.clusteringco.FonlIdGCC"
	;;
	"LCC_Id")  main_class="graph.clusteringco.FonlIdLCC"
	;;
	"TC_Id")    main_class="graph.clusteringco.FonlIdTC"
	;;
	"GCC_GraphX") main_class="graph.clusteringco.GraphX_GCC"
	;;
	"LCC_GraphX") main_class="graph.clusteringco.GraphX_LCC"
	;;
	"TC_GraphX") main_class="graph.clusteringco.GraphX_TC"
	;;
	"GCC_NodeIter") main_class="graph.clusteringco.NodeIteratorPlusGCC_Spark"
	;;
	"TC_NodeIter")  main_class="graph.clusteringco.NodeIteratorPlusTC_Spark"
	;;
	"TC_Cohen") main_class="graph.clusteringco.CohenTC"
	;;
	"TC_Pregel") main_class="graph.clusteringco.PregelTC"
	;;
	*)	echo "please determine your task in the argument
	[GCC_Hob|GCC_Deg|LCC_Deg|TC_Deg|GCC_Id|LCC_Id|GCC_GraphX|LCC_GraphX|TC_GraphX|GCC_NodeIter|TC_NodeIter"
		exit 1
esac

dataset="$HOME/$2"
p="$3"

if [ "$master" != "localhost" ]; then
	dataset="hdfs://$master/graph-data/$2"
fi

cd $SPARK_HOME

echo "Running $main_class on $master with partitions $p"
bin/spark-submit --class $main_class --total-executor-cores $total_cores --master spark://$master:7077 $jar_path $dataset $p $4 $persistOnDisk $repartition $userSort
