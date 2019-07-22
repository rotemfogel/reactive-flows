# Reactive Flows #

Reactive Flows is a demo project showing a Reactive web app built with:

- Akka Actors
- Akka HTTP
- Akka SSE (server-sent events)
- Akka Distributed Data
- Akka Cluster Sharding
- Akka Persistence
- AngularJS
- Cassandra
- Scala
- etcd

## Usage

- Important: Reactive Flows makes use of [server-sent events](https://www.w3.org/TR/eventsource) and [advanced JavaScript](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Array/find) which aren't available for all browsers, so make sure to use Firefox > 25.0, Safari > 7.1 or Chrome > 45.0
- To run a single node, simply execute `reStart` in an sbt session; you can shutdown the app with `reStop`
- To run multiple nodes, build a Docker image with `docker:publishLocal` and execute  the script `bin/run-reactive-flows.sh` without an argument or a number from [0, 10)
- As the names and labels of the flows aren't persisted, you have to create them after the app has started; see below examples
- Important: Reactive Flows uses [ConstructR](https://github.com/hseeberger/constructr) for initializing the cluster; make sure etcd is started and available
- Important: Reactive Flows uses the Cassandra plugin for Akka Persistence; make sure Cassandra is started and available under the configured contact point

### Run 

#### Running a single node etcd
Use the host IP address when configuring etcd:
````
export NODE1=192.168.1.21
````

Configure a Docker volume to store etcd data:
````
docker volume create --name etcd-data
export DATA_DIR="etcd-data"
````
Run the latest version of etcd:

````
docker run \
  -d \
  -p 2379:2379 \
  -p 2380:2380 \
  --volume=${DATA_DIR}:/etcd-data \
  --name etcd quay.io/coreos/etcd:latest \
  /usr/local/bin/etcd \
  --data-dir=/etcd-data --name node1 \
  --initial-advertise-peer-urls http://${NODE1}:2380 --listen-peer-urls http://0.0.0.0:2380 \
  --advertise-client-urls http://${NODE1}:2379 --listen-client-urls http://0.0.0.0:2379 \
  --initial-cluster node1=http://${NODE1}:2380
````

#### Run scylladb
````
docker run -d --name scylla \
    -p 10000:10000 \
    -p 7000:7000 \
    -p 7001:7001 \
    -p 9042:9042 \
    -p 9160:9160 \
    -p 9180:9180 \
    scylladb/scylla:latest
````

## REST API ##

```
http://localhost:8000/flows
http://localhost:8000/flows?label=Akka
http://localhost:8000/flows?label=Scala
http://localhost:8000/flows/akka/posts?text='Akka rocks!'
http://localhost:8000/flows/akka/posts?text='Akka really rocks!'
http://localhost:8000/flows/scala/posts?text='Scala rocks, too!'
http://localhost:8000/flows/scala/posts?text='Scala really rocks, too!'
http://localhost:8000/flows/akka/posts?count==99
```

### Examples ###
```
curl -XPOST -H "Content-Type: application/json" \
    http://localhost:8000/flows -d '{"label":"akka"}'
    
curl -XPOST -H "Content-Type: application/json" \
    http://localhost:8000/flows/akka/posts -d '{"text": "akka is cool!"}'

while (true); 
do 
    curl -XPOST -H "Content-Type: application/json" \
        http://localhost:8000/flows/akka/posts -d '{"text": "akka is cool!"}'; 
    sleep 2; 
done

```
## License ##

This code is open source software licensed under the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0).
