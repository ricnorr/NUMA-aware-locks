# run from NUMA-aware-locks directory
#!/bin/bash
DIR=${CONDA_PREFIX:-$HOME/miniconda3}
CONDA_PROFILE=$DIR/etc/profile.d/conda.sh
source "$CONDA_PROFILE"
conda activate pip3
sdk use java 19.0.2-open

cp settings/settings.json fatjar/settings/settings.json
cd fatjar
java -XX:+UseParallelGC -XX:+UseNUMA --enable-preview -jar NUMA-aware-locks.jar | ts
cp ./results/benchmark_results.csv ../results/benchmark_results.csv
rm -rf ./results/benchmark_results.csv
cd ../scripts/
python3 picture_creator
cd ..