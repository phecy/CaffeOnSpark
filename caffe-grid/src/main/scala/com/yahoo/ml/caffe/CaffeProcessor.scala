// Copyright 2016 Yahoo Inc.
// Licensed under the terms of the Apache 2.0 license.
// Please see LICENSE file in the project root for terms.
package com.yahoo.ml.caffe

import java.util.concurrent.{ArrayBlockingQueue, ForkJoinPool, ConcurrentHashMap}
import java.util.ArrayList

import caffe.Caffe._
import com.yahoo.ml.jcaffe._
import org.slf4j.{LoggerFactory, Logger}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, ExecutionContext}
import org.apache.spark.sql.Row

private[caffe] object CaffeProcessor {
  var myInstance: CaffeProcessor[_, _] = null

  def instance[T1, T2](source: DataSource[T1, T2], rank: Int): CaffeProcessor[T1, T2] = {
    myInstance = new CaffeProcessor[T1, T2](source, rank)
    myInstance.asInstanceOf[CaffeProcessor[T1, T2]]
  }

  def instance[T1, T2](): CaffeProcessor[T1, T2] = {
    myInstance.asInstanceOf[CaffeProcessor[T1, T2]]
  }
}

private[caffe] class QueuePair[T]  {
  val Free: ArrayBlockingQueue[T] = new ArrayBlockingQueue[T] (2)
  val Full: ArrayBlockingQueue[T] = new ArrayBlockingQueue[T] (2)
}

private[caffe] class CaffeProcessor[T1, T2](val source: DataSource[T1, T2],
                                             val rank: Int) {
  val log: Logger = LoggerFactory.getLogger(this.getClass)
  log.info("my rank is " + rank)
  //initialize source
  if (!source.init()) {
    throw new Exception("Failed to initialize data source")
  }
  val conf = source.conf
  val solverMode: Int = source.solverParameter.getSolverMode().getNumber()
  val numLocalGPUs: Int = conf.devices
  val numTotalGPUs: Int = numLocalGPUs * conf.clusterSize
  assert(source != null)
  implicit val exec = ExecutionContext.fromExecutorService(new ForkJoinPool(numLocalGPUs * (conf.transform_thread_per_device + 1)))
  val transformers: ArrayList[Future[_]] = new ArrayList[Future[_]]
  val solvers: ArrayList[Future[_]] = new ArrayList[Future[_]]
  var rdmaStarted = false
  var threadsStarted = false
  val objectHolder: ConcurrentHashMap[Object, Object] = new ConcurrentHashMap[Object, Object]()
  val snapshotInterval =  source.solverParameter.getSnapshot()
  var STOP_MARK: (Array[String], Array[FloatBlob], FloatBlob) =  (Array[String](), Array(), new FloatBlob())
  var results: ArrayList[Row] = new ArrayList[Row]
  var solversFinished = false
  val localModelPath : String = {
    if (source.isTrain) ""
    else FSUtils.GetLocalFileName(conf.modelPath, "model.tmp")
  }

  //create a list of caffeTops
  val caffeNetList: Seq[CaffeNet] = {
    if (source.isTrain) {
      // resume training if available
      val localStateFile: String = FSUtils.GetLocalFileName(conf.snapshotStateFile, "state.tmp")
      val localModelFile: String = FSUtils.GetLocalFileName(conf.snapshotModelFile, "model.tmp")
      Seq(new CaffeNet(conf.protoFile, localModelFile, localStateFile, numLocalGPUs,
          conf.clusterSize, rank, true, conf.connection, -1))
    } else {
      // feature or test mode, we have numLocalGPUs caffeTops, each of them has one gpu.
      // this is to avoid create master/slave gpus where slave gpu does not do test.
      var startGPUIdx = -1
      var seq : Seq[CaffeNet] = Seq()
      for (g <- 0 until numLocalGPUs){
        val caffeNet = new CaffeNet(conf.protoFile, localModelPath, "", 1,
          1, 0, false, CaffeNet.NONE, startGPUIdx)
        //in order to get GPU, we need to initialize P2P Sync 1st
        caffeNet.connect(null)
        startGPUIdx = caffeNet.deviceID(0)
        seq = seq :+ caffeNet
      }
      seq
    }
  }

  //comma separated list of RDMA address
  def getLocalAddress(): Array[String] = {
    caffeNetList(0).localAddresses
  }

  def getTestOutputBlobNames(): Array[String] = {
    caffeNetList(0).getTestOutputBlobNames.split(',')
  }

  //start the processor
  def start(rank2addresses: Array[(Int, Array[String])]) : Unit = {
    if (source.isTrain) {
      val peer_addr = new Array[String](rank2addresses.length)
      for ((peer_rank, addrs) <- rank2addresses) {
          if (peer_rank != rank)
            peer_addr(peer_rank) = addrs(rank)
      }
      caffeNetList(0).connect(peer_addr)
    }

    //clear the source queue
    source.sourceQueue.clear()

    //start worker threads
    startThreads()
  }

  //start threads for transformers and solvers
  private def startThreads(): Unit = {
    //start threads only once for JVM
    if (threadsStarted) return
    results.clear
    solvers.clear
    transformers.clear


    for (g <- 0 until numLocalGPUs) {
      val queuePair = new QueuePair[(Array[String], Array[FloatBlob], FloatBlob)]()

      if (source.isTrain) {
        //start solvers w/ only rank 0 will save model
        solvers.add(Future {
          doTrain(caffeNetList(0), g, queuePair)
        })
        //start transformers
        for (t <- 0 until conf.transform_thread_per_device)
          transformers.add(Future {
            doTransform(caffeNetList(0), g, queuePair, g)
          })
      } else {
        //start solvers for test
        solvers.add(Future {
          doFeatures(caffeNetList(g), 0, queuePair)
        })
        //start transformers
        for (t <- 0 until conf.transform_thread_per_device)
          transformers.add(Future {
            doTransform(caffeNetList(g), 0, queuePair, g)
          })
      }
    }
    
    threadsStarted = true
  }

  // sync the executors
  def sync(): Unit = {
    if (source.isTrain)
      caffeNetList(0).sync
  }

  //feed data to train queue
  def feedQueue(item: T1): Boolean = {
    var offer_status = false
    while (!solvers.get(0).isCompleted && !offer_status) {
      offer_status = source.sourceQueue.offer(item)
    }
    !solvers.get(0).isCompleted
  }

  //stop all threads
  def stopThreads(): Unit = {
    //send stop signals
    for (i <- 0 until conf.transform_thread_per_device * numLocalGPUs)
      feedQueue(source.STOP_MARK)
    //stop transformers & solvers
    import scala.collection.JavaConversions._
    for (solver <- solvers) Await.result(solver, Duration.Inf)
    for (transformer <- transformers) Await.result(transformer, Duration(1, "nanos"))
    threadsStarted = false
  }

  //stop all threads and the pool
  def stop(): Unit = {
    if (threadsStarted) {
      stopThreads()
    }
    exec.shutdown()
  }

  private def takeFromQueue(queue: ArrayBlockingQueue[(Array[String], Array[FloatBlob], FloatBlob)], queueIdx: Int): (Array[String], Array[FloatBlob], FloatBlob) = {
    var tpl: (Array[String], Array[FloatBlob], FloatBlob) = null
    while (!solvers.get(queueIdx).isCompleted && tpl==null)
      tpl = queue.peek()

    if (solvers.get(queueIdx).isCompleted) return null
    queue.take()
  }

  private def putIntoQueue(tpl:(Array[String], Array[FloatBlob], FloatBlob), queue : ArrayBlockingQueue[(Array[String], Array[FloatBlob], FloatBlob)], queueIdx: Int): Unit = {
    var status = false
    while (!solvers.get(queueIdx).isCompleted && status==false)
        status = queue.offer(tpl)
  }

  private def initialFreeQueue(queuePair: QueuePair[(Array[String], Array[FloatBlob], FloatBlob)]): Unit = {
    val batchSize = source.batchSize()
    for (j <- queuePair.Free.remainingCapacity() to 1 by -1) {
      val datablob: Array[FloatBlob] = source.dummyDataBlobs()
      val labelblob: FloatBlob = new FloatBlob()
      labelblob.reshape(batchSize, 1, 1 ,1)
      queuePair.Free.put((new Array[String](batchSize), datablob, labelblob))
    }
  }

  private def doTransform(caffeNet: CaffeNet, solverIdx: Int,
                          queuePair: QueuePair[(Array[String], Array[FloatBlob], FloatBlob)],
                          queueIdx: Int): Unit = {

    //This will eliminate data copy by solver thread
    caffeNet.init(solverIdx)
    
    var transformer: FloatDataTransformer = null
    if (source.transformationParameter != null) {
      transformer = new FloatDataTransformer(source.transformationParameter, source.isTrain)
    }

    try {
      var data: Array[FloatBlob] = if (transformer != null) source.dummyDataBlobs() else null
      val batchSize = source.batchSize()
      val dataHolder = source.dummyDataHolder()
      val labels = new FloatBlob()
      labels.reshape(batchSize, 1, 1, 1)
      val sampleIds = new Array[String](batchSize)

      //initialize free queue now that device is set
      initialFreeQueue(queuePair)

      while (!solvers.get(solverIdx).isCompleted && source.nextBatch(sampleIds, dataHolder, labels)) {
        if (transformer != null) {
          dataHolder match {
            case matVector: MatVector => {
              transformer.transform(matVector, data(0))
	        }
            case _ => throw new Exception("Unsupported data type for transformer")
          }
        }

        /* push the data/lablels to solver thread */
        var tpl = takeFromQueue(queuePair.Free, queueIdx)
        if (tpl !=null) {
          sampleIds.copyToArray(tpl._1)
          if (transformer != null) {
            for (vidx <- 0 until data.size)
              tpl._2(vidx).copyFrom(data(vidx))
          }
          else {
            dataHolder match {
              case dataBlobs: Seq[FloatBlob @unchecked] => {
                for (vidx <- 0 until dataBlobs.size)
                  tpl._2(vidx).copyFrom(dataBlobs(vidx))
              }
              case _ => throw new Exception("Untranformed data type must be FloatBlob")
            }
          }
          tpl._3.copyFrom(labels)
          putIntoQueue(tpl, queuePair.Full, queueIdx)
        }
      }
    }
    catch {
      case ex: Exception => {
        log.error("Transformer thread failed", ex)
        throw ex
      }
    } finally {
      takeFromQueue(queuePair.Free, queueIdx)
      putIntoQueue(STOP_MARK, queuePair.Full, queueIdx)
    }

  }

  private def toDataPtr(from: FloatBlob): FloatArray = {
    if (solverMode == SolverParameter.SolverMode.CPU_VALUE) from.cpu_data()
    else from.gpu_data()
  }

  private def doTrain(caffeNet: CaffeNet, syncIdx: Int,
                      queuePair: QueuePair[(Array[String], Array[FloatBlob], FloatBlob)]): Unit = {
    try {
      val isRootSolver: Boolean = (syncIdx == 0)
      val snapshotPrefix: String = source.solverParameter.getSnapshotPrefix()
      val modelFilePrefix: String = conf.modelPath.substring(0, conf.modelPath.lastIndexOf("/") + 1)

      var tpl: (Array[String], Array[FloatBlob], FloatBlob) = null
      val initIter: Int = caffeNet.getInitIter(syncIdx)
      val maxIter: Int = caffeNet.getMaxIter(syncIdx)
      caffeNet.init(syncIdx, true)
      for (it <- initIter until maxIter if (tpl != STOP_MARK)) {
        tpl = queuePair.Full.take
        if (tpl == STOP_MARK)  {
          queuePair.Free.put(tpl)
        } else {
          caffeNet.train(syncIdx, tpl._2, toDataPtr(tpl._3))
          queuePair.Free.put(tpl)

          if ((rank == 0) && isRootSolver && (snapshotInterval > 0) && ((it + 1) % snapshotInterval == 0)) {
            log.info("Snapshot saving into files at iteration #" + (it + 1))
            val modelFilename: String = modelFilePrefix + snapshotPrefix + "_iter_" + (it + 1)
            FSUtils.GenModelOrState(caffeNet, modelFilename, true)
          }
        }
      }

      if ((rank == 0) && isRootSolver) {
        log.info("Model saving into file at the end of training:" + conf.modelPath)
        FSUtils.GenModelOrState(caffeNet, conf.modelPath, false)
      }
    } catch {
      case ex: Exception => {
        log.error("Train solver exception", ex)
      }
    }

  }

  private def doFeatures(caffeNet: CaffeNet, syncIdx: Int,
                     queuePair: QueuePair[(Array[String], Array[FloatBlob], FloatBlob)]): Unit = {
    try {
      var blobNames = conf.features
      if (conf.isTest)
        blobNames = getTestOutputBlobNames()
      var act_iter: Int = 0
      var tpl: (Array[String], Array[FloatBlob], FloatBlob) = null
      val max_iter: Int = caffeNet.getMaxIter(syncIdx)
      val batchSize = source.batchSize()
      val bl = blobNames.length
      caffeNet.init(syncIdx, true)
      while (act_iter < max_iter && tpl != STOP_MARK) {
        tpl = queuePair.Full.take
        if (tpl == STOP_MARK) {
          queuePair.Free.put(tpl)
        } else {
          val top_vec = caffeNet.predict(syncIdx, tpl._2, toDataPtr(tpl._3), blobNames)
          val dim_features: Seq[Int] = (0 until bl).map{i => top_vec(i).count/batchSize}
          for (i <- 0 until batchSize) {
            // processing the result row by row
            // first item is the SampleID
            var result: Array[_] = Array(tpl._1(i))
            for (j <- 0 until bl) {
              val blob = top_vec(j)
              val offset:Int = dim_features(j) * i
              // If dim_feature(j) == 0, the layer does aggregation.
              // We repeat the feature values for individual samples in the batch.
              // To avoid this, batch size = 1 is recommended.
              val featureSize = if (dim_features(j) > 0) dim_features(j) else blob.count
              val fv = new Array[Float](featureSize)
              for (k <- 0 until featureSize) {
                fv(k) = blob.cpu_data().get(k + offset)
              }
              result = result :+ fv
            }
            results synchronized {
              results.add(Row.fromSeq(result))
            }
          }
          queuePair.Free.put(tpl)
          act_iter += 1
        }
      }
    } catch {
      case ex: Exception => {
        log.error("Test/Feature solver exception", ex)
        throw ex
      }
    }
  }
}
