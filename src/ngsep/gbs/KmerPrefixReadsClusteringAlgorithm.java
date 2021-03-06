/*******************************************************************************
 * NGSEP - Next Generation Sequencing Experience Platform
 * Copyright 2016 Jorge Duitama
 *
 * This file is part of NGSEP.
 *
 *     NGSEP is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     NGSEP is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with NGSEP.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package ngsep.gbs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import ngsep.discovery.MultisampleVariantsDetector;
import ngsep.discovery.VariantPileupListener;
import ngsep.main.CommandsDescriptor;
import ngsep.main.OptionValuesDecoder;
import ngsep.main.ProgressNotifier;
import ngsep.sequences.DNASequence;
import ngsep.sequences.DNAShortKmer;
import ngsep.sequences.DNAShortKmerClusterMap;
import ngsep.sequences.RawRead;
import ngsep.sequences.io.FastqFileReader;
import ngsep.variants.GenomicVariant;
import ngsep.variants.Sample;
import ngsep.vcf.VCFFileHeader;
import ngsep.vcf.VCFFileWriter;
import ngsep.vcf.VCFRecord;

/**
 * @author Jorge Gomez
 * @author Jorge Duitama
 * @author Andrea Parra
 */
public class KmerPrefixReadsClusteringAlgorithm {

	// Constants for default values
	public static final String DEF_INPUT_DIRECTORY = ".";
	
	
	public static final int DEF_KMER_LENGTH = 31;
	public static final int DEF_NUM_THREADS = 1;
	public static final double DEF_HETEROZYGOSITY_RATE_DIPLOID = MultisampleVariantsDetector.DEF_HETEROZYGOSITY_RATE_DIPLOID;
	public static final short DEF_MIN_QUALITY = MultisampleVariantsDetector.DEF_MIN_QUALITY;
	public static final byte DEF_MAX_BASE_QS = VariantPileupListener.DEF_MAX_BASE_QS;
	public static final byte DEF_PLOIDY = GenomicVariant.DEFAULT_PLOIDY;
	public static final double DEF_MIN_ALLELE_FREQUENCY = 0;
	public static final int DEF_START = 8;
	public static final int DEF_MAX_NUM_CLUSTERS = 2000000;
	public static final int DEF_MAX_READS_IN_MEMORY = 4000000;
	public static final String DEF_REGEXP_SINGLE="<S>.fastq.gz";
	public static final String DEF_REGEXP_PAIRED="<S>_<N>.fastq.gz";
	
	public static final String PAIRED_END_READS_SEPARATOR = "NNNNNNNNNNNNNNNNNNNN";
	public static final String PAIRED_END_READS_QS = "00000000000000000000";
	
	
	// Logging and progress
	private Logger log = Logger.getLogger(KmerPrefixReadsClusteringAlgorithm.class.getName());
	private ProgressNotifier progressNotifier = null;	
	
	// Parameters
	private String inputDirectory = DEF_INPUT_DIRECTORY;
	private String outputPrefix=null;
	private String filesDescriptor = null;
	private int kmerLength = DEF_KMER_LENGTH;
	private int maxNumClusters = DEF_MAX_NUM_CLUSTERS;
	private int numThreads = DEF_NUM_THREADS;
	private double heterozygosityRate = DEF_HETEROZYGOSITY_RATE_DIPLOID;
	private byte maxBaseQS = DEF_MAX_BASE_QS;
	private short minQuality = DEF_MIN_QUALITY;
	private byte normalPloidy = DEF_PLOIDY;
	private double minAlleleFrequency = DEF_MIN_ALLELE_FREQUENCY;
	
	// Model attributes
	private static final String READID_SEPARATOR="$";
	private final int MAX_TASK_COUNT = 20;
	
	private int minClusterDepth = 10;
	private int maxClusterDepth = 1000;
	
	//Variables for parallel VCF
	private Map<String, String> filenamesBySampleId1=new HashMap<>();
	private Map<String, String> filenamesBySampleId2=new HashMap<>();
	private DNAShortKmerClusterMap kmersMap;
	private int[] clusterSizes;
	private List<Sample> samples = new ArrayList<>();
	
	private ProcessInfo processInfo = new ProcessInfo();
	
	// Statistics
	private int numClustersWithCalledVariants = 0;
	private int numClustersWithGenVariants = 0;
	private int numClusteredFiles;
	private int numUnclusteredReadsI = 0;
	private int numReadsLargeClusters = 0;
	private int numReadsSmallClusters = 0;
	private int numLargeClusters = 0;
	private int numSmallClusters = 0;
	private int numTotalReads = 0;
	
	// Get and set methods
	public Logger getLog() {
		return log;
	}
	public void setLog(Logger log) {
		this.log = log;
	}
	
	public ProgressNotifier getProgressNotifier() {
		return progressNotifier;
	}
	public void setProgressNotifier(ProgressNotifier progressNotifier) { 
		this.progressNotifier = progressNotifier;
	}
	
	public String getInputDirectory() {
		return inputDirectory;
	}
	public void setInputDirectory(String inputDirectory) {
		this.inputDirectory = inputDirectory;
	}
	
	public String getOutputPrefix() {
		return outputPrefix;
	}
	public void setOutputPrefix(String outputPrefix) {
		this.outputPrefix = outputPrefix;
	}
	
	public String getFilesDescriptor() {
		return filesDescriptor;
	}
	public void setFilesDescriptor(String filesDescriptor) {
		this.filesDescriptor = filesDescriptor;
	}
	
	public int getKmerLength() {
		return kmerLength;
	}
	public void setKmerLength(int kmerLength) {
		if(kmerLength<=0) throw new IllegalArgumentException("Kmer length should be a positive number");
		this.kmerLength = kmerLength;
	}
	public void setKmerLength(String value) {
		setKmerLength((int)OptionValuesDecoder.decode(value, Integer.class));
	}
	
	public int getMaxNumClusters() {
		return maxNumClusters;
	}
	public void setMaxNumClusters(int maxNumClusters) {
		if(maxNumClusters<=0) throw new IllegalArgumentException("Maximum number of clusters should be a positive number");
		this.maxNumClusters = maxNumClusters;
	}
	public void setMaxNumClusters(String value) {
		setMaxNumClusters((int)OptionValuesDecoder.decode(value, Integer.class));
	}

	public int getNumThreads() {
		return numThreads;
	}
	public void setNumThreads(int numThreads) {
		if(numThreads<=0) throw new IllegalArgumentException("Number of threads should be a positive number");
		this.numThreads = numThreads;
	}
	public void setNumThreads(String value) {
		setNumThreads((int)OptionValuesDecoder.decode(value, Integer.class));
	}

	public double getHeterozygosityRate() {
		return heterozygosityRate;
	}
	public void setHeterozygosityRate(double heterozygosityRate) {
		if(heterozygosityRate<0) throw new IllegalArgumentException("Heterozygosity rate should be a non-negative number");
		if(heterozygosityRate>1) throw new IllegalArgumentException("Heterozygosity rate should be a number from 0 to 1");
		this.heterozygosityRate = heterozygosityRate;
	}
	public void setHeterozygosityRate(String value) {
		setHeterozygosityRate((double)OptionValuesDecoder.decode(value, Double.class));
	}
	
	public byte getMaxBaseQS() {
		return maxBaseQS;
	}
	public void setMaxBaseQS(byte maxBaseQS) {
		if(maxBaseQS<=0) throw new IllegalArgumentException("Maximum base quality score should be a positive number");
		this.maxBaseQS = maxBaseQS;
	}
	public void setMaxBaseQS(String value) {
		setMaxBaseQS((byte)OptionValuesDecoder.decode(value, Byte.class));
	}
	
	public short getMinQuality() {
		return minQuality;
	}
	public void setMinQuality(short minQuality) {
		if(minQuality<0) throw new IllegalArgumentException("Minimum variant quality should be a non-negative number");
		this.minQuality = minQuality;
	}
	public void setMinQuality(String value) {
		setMinQuality((byte)OptionValuesDecoder.decode(value, Short.class));
	}
	
	public byte getNormalPloidy() {
		return normalPloidy;
	}
	public void setNormalPloidy(byte normalPloidy) {
		if(normalPloidy<=0) throw new IllegalArgumentException("Normal ploidy should be a positive number");
		this.normalPloidy = normalPloidy;
	}
	public void setNormalPloidy(String value) {
		setNormalPloidy((byte)OptionValuesDecoder.decode(value, Byte.class));
	}
	
	public double getMinAlleleFrequency() {
		return minAlleleFrequency;
	}
	/**
	 * @param minAlleleFrequency the minAlleleFrequency to set
	 */
	public void setMinAlleleFrequency(double minAlleleFrequency) {
		this.minAlleleFrequency = minAlleleFrequency;
	}
	public void setMinAlleleFrequency(String value) {
		setMinAlleleFrequency((double)OptionValuesDecoder.decode(value, Double.class));
	}

	/**
	 * @return the samples
	 */
	public List<Sample> getSamples() {
		return samples;
	}
	
	public static void main(String[] args) throws Exception {
		KmerPrefixReadsClusteringAlgorithm instance = new KmerPrefixReadsClusteringAlgorithm();
		CommandsDescriptor.getInstance().loadOptions(instance, args);
		instance.run();
	}

	public void run() throws IOException, InterruptedException {
		System.out.print("Running in debugging mode");
		processInfo.addTime(System.currentTimeMillis(), "Load files start");
		loadFilenamesAndSamples();
		processInfo.addTime(System.currentTimeMillis(), "Load files end");
		processInfo.addTime(System.currentTimeMillis(), "BuildKmersMap start");
		buildSamples();
		int nSamples = samples.size();
		minClusterDepth = nSamples;
		maxClusterDepth = 100*nSamples;
		log.info("Loaded "+samples.size()+" samples. Min depth: "+minClusterDepth+". Max cluster depth: "+maxClusterDepth);
		buildKmersMap();
		processInfo.addTime(System.currentTimeMillis(), "BuildKmersMap end");
		processInfo.addTime(System.currentTimeMillis(), "Cluster reads start");
		log.info("Built kmers map with "+kmersMap.size()+" clusters");
		this.clusterSizes = new int[kmersMap.size()];
		List<String> clusteredReadsFilenames = clusterReads();
		kmersMap.dispose();
		printDistribution();
		printStatistics("initial");
		processInfo.addTime(System.currentTimeMillis(), "Cluster reads end");	
		processInfo.addTime(System.currentTimeMillis(), "Variant calling start");		
//		List<String> clusteredReadsFilenames = debug();
		this.numClusteredFiles = clusteredReadsFilenames.size();
		log.info("Clustered reads");
		callVariants(clusteredReadsFilenames);
		processInfo.addTime(System.currentTimeMillis(), "Variant calling end");
		log.info("Called variants");
		printStatistics("final");
		log.info("Process finished");
	}
	
	
	private void buildSamples() {
		for(String sampleName: filenamesBySampleId1.keySet()){
			Sample sample = new Sample(sampleName);
			sample.setNormalPloidy(normalPloidy);
			sample.addReadGroup(sampleName);
			samples.add(sample);
		}
	}
	
	private void printDistribution() throws IOException {
		int[] dist = getClusterSizeDist();
		log.info("Printing cluster distribution.");
		try(PrintStream distribution = new PrintStream(outputPrefix+"_clusterDist.txt");){
			distribution.println("clusterSize\tfrequency");
			for(int i = 0; i < dist.length; i++) {
				distribution.println(i + "\t" + dist[i]);
			}
		}
	}
	
	private int[] getClusterSizeDist() {
		int max = 0;
		for(int size: this.clusterSizes) {
			if(size>=max) {
				max = size;
			}
		}
		int[] dist = new int[max+1];
		for(int size: this.clusterSizes) {
			dist[size]++;
		}
		return dist;
	}

	private void loadFilenamesAndSamples() throws IOException {
		if (filesDescriptor != null) {
			loadFilenamesAndSamplesPairedEnd();
		} else {
			File[] files = (new File(inputDirectory)).listFiles();
			for(File f : files) {
				String filename = f.getName();
				int i = filename.indexOf(".fastq");
				if(i>=0) {
					String sampleId = filename.substring(0, i);
					filenamesBySampleId1.put(sampleId, f.getAbsolutePath());
				}
			}
		}
	}
	
	private void loadFilenamesAndSamplesPairedEnd() throws IOException {
		try (BufferedReader descriptor = new BufferedReader(new FileReader(filesDescriptor))) {
			//descriptor.readLine();
			String line = descriptor.readLine();
			while(line != null) {
				String[] payload = line.split("\t");
				
				String sampleId = payload[0];
				String f1 = payload[1];
				String f2 = payload[2];
				filenamesBySampleId1.put(sampleId, inputDirectory + f1);
				filenamesBySampleId2.put(sampleId, inputDirectory + f2);
				line = descriptor.readLine();
			}
		}
	}
	
	public void buildKmersMap() throws IOException {
		kmersMap = new DNAShortKmerClusterMap(kmerLength,maxNumClusters);
		log.info("Initialized k-mers map");
		int n=0;
		for(String filename:filenamesBySampleId1.values()) {
			log.info("Processing file "+filename);
			addKmersFromFile(filename);
			n++;
			log.info(kmersMap.getNumClusters() + " clusters created for " + n + " files.");
		}
	}

	private void addKmersFromFile(String filename) throws IOException {
		int readCount = 0;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				RawRead read = reader.next();
				String s = read.getSequenceString();
				if(DEF_START + kmerLength>s.length()) continue;
				String prefix = s.substring(DEF_START,DEF_START + kmerLength);
				if(DNASequence.isDNA(prefix)) {
					kmersMap.addOcurrance(new DNAShortKmer(prefix));
					readCount++;
				}
			}
		}
		log.info("Processed a total of " + readCount + " reads for file: "+filename);
	}
	public List<String> clusterReads() throws IOException {
		ClusteredReadsCache clusteredReadsCache = new ClusteredReadsCache(); 
		for(String sampleId:filenamesBySampleId1.keySet()) {
			String filename1 = filenamesBySampleId1.get(sampleId);
			String filename2 = filenamesBySampleId2.get(sampleId);
			if(filename2 == null) {
				log.info("Clustering reads from " + filename1);
				clusterReadsSingleFile (sampleId, filename1, clusteredReadsCache);
			} else {
				clusterReadsPairedEndFiles (sampleId, filename1, filename2, clusteredReadsCache);
			}
		}
		clusteredReadsCache.dump(outputPrefix);
		return clusteredReadsCache.getClusteredReadFiles();
	}

	private void clusterReadsSingleFile(String sampleId, String filename, ClusteredReadsCache clusteredReadsCache) throws IOException {
		int unmatchedReads = 0;
		int count = 1;
		try (FastqFileReader openFile = new FastqFileReader(filename);) {
			Iterator<RawRead> reader = openFile.iterator();
			while(reader.hasNext()) {
				this.numTotalReads++;
				RawRead read = reader.next();
				String s = read.getSequenceString();
				if(DEF_START + kmerLength>s.length()) continue;
				String prefix = s.substring(DEF_START,DEF_START + kmerLength);
				if(!DNASequence.isDNA(prefix)) continue;
				Integer clusterId = kmersMap.getCluster(new DNAShortKmer(prefix));
				if(clusterId==null) {
					this.numUnclusteredReadsI++;
					unmatchedReads++;
					continue;
				}
				clusterSizes[clusterId]++;
				if(clusterSizes[clusterId]<=maxClusterDepth) {
					clusteredReadsCache.addSingleRead(clusterId, new RawRead(sampleId+READID_SEPARATOR+clusterId+READID_SEPARATOR+read.getName(), s, read.getQualityScores()));
				}
				if(clusteredReadsCache.getTotalReads()>=DEF_MAX_READS_IN_MEMORY) {
					log.info("dumping reads");
					clusteredReadsCache.dump(outputPrefix);
				}
				count++;
			}
			log.info(Integer.toString(unmatchedReads) + " reads remained unmatched for file: " + filename);
			log.info(Integer.toString(count) + " reads were succesfully matched for file: " + filename);
		}
	}
	

	private void clusterReadsPairedEndFiles(String sampleId, String filename1, String filename2, ClusteredReadsCache clusteredReadsCache) throws IOException {
		int unmatchedReads = 0;
		int count = 0;
		try (FastqFileReader file1 = new FastqFileReader(filename1);
			 FastqFileReader file2 = new FastqFileReader(filename2)) {
			Iterator<RawRead> it1 = file1.iterator();
			Iterator<RawRead> it2 = file2.iterator();
			while(it1.hasNext() && it2.hasNext()) {
				this.numTotalReads += 2;
				RawRead read1 = it1.next();
				RawRead read2 = it2.next();
				
				String read1s = read1.getSequenceString();
				if(DEF_START + kmerLength > read1s.length()) continue;
				String prefix = read1s.substring(DEF_START,DEF_START + kmerLength);
				if(!DNASequence.isDNA(prefix)) continue;
				
				Integer clusterId = kmersMap.getCluster(new DNAShortKmer(prefix));
				if(clusterId==null) {
					this.numUnclusteredReadsI++;
					unmatchedReads++;
					continue;
				}
				
				clusterSizes[clusterId]++;
				if(clusterSizes[clusterId]<=maxClusterDepth) {
					String read2s = read2.getSequenceString();
					String mergedRead = String.format("%s%s%s", read1s, PAIRED_END_READS_SEPARATOR, read2s);
					String mergedScores = String.format("%s%s%s", read1.getQualityScores(), PAIRED_END_READS_QS, read2.getQualityScores());
					clusteredReadsCache.addSingleRead(clusterId, new RawRead(sampleId+READID_SEPARATOR+clusterId+READID_SEPARATOR+read1.getName(), mergedRead, mergedScores));
				}
				if(clusteredReadsCache.getTotalReads()>=DEF_MAX_READS_IN_MEMORY) {
					log.info("dumping reads");
					clusteredReadsCache.dump(outputPrefix);
				}
				
				count++;
			}
			
			log.info(String.format("%d reads remained unmatched for files: %s, %s", unmatchedReads, filename1, filename2));
			log.info(String.format("%d reads were succesfully matched for files: %s, %s",count ,filename1, filename2));
		}
		return;
	}
	
	public void callVariants(List<String> clusteredReadsFilenames) throws IOException, InterruptedException {
		int numberOfFiles = clusteredReadsFilenames.size();

		for(int size: this.clusterSizes) {
			if(size>maxClusterDepth) {
				this.numLargeClusters++;
				this.numReadsLargeClusters += size;
			}
			if(size<minClusterDepth) {
				this.numSmallClusters++;
				this.numReadsSmallClusters += size;
			}
		}
		
		//process files in parallel
		FastqFileReader [] readers = new FastqFileReader[numberOfFiles];
		RawRead [] currentReads = new RawRead[numberOfFiles];
		VCFFileHeader header = VCFFileHeader.makeDefaultEmptyHeader();
		VCFFileWriter writer = new VCFFileWriter ();
		
		// add samples to header
		for(Sample sample: this.samples) {
			header.addSample(sample, false);
		}
		
		Arrays.fill(currentReads, null);
		List<Iterator<RawRead>> iterators = new ArrayList<>();
		
		//Create pool manager and statistics
		ThreadPoolManager poolManager = new ThreadPoolManager(numThreads, MAX_TASK_COUNT);
		
		//Timer for mem checks
		Timer timer = new Timer();
		
		try (PrintStream outVariants = new PrintStream(outputPrefix+"_variants.vcf");
			 PrintStream outConsensus = new PrintStream(outputPrefix+"_consensus.fa");
			 PrintStream memUsage = new PrintStream(outputPrefix + "_memoryUsage.txt");) {
			int numNotNull = 0;
			int numCluster = 0;
			
			// save memory usage every 5 seconds
			memUsage.println("Time(ms)\tMemoryUsage(MB)");
			timer.schedule(new MemoryUsage(memUsage), 0, 5000);
			
			for(int i=0; i<numberOfFiles; i++) {
				readers[i] = new FastqFileReader(clusteredReadsFilenames.get(i));
				Iterator<RawRead> it = readers[i].iterator();
				iterators.add(it);
				if(it.hasNext()) {
					currentReads[i] = it.next();
					numNotNull++;
				}
			}
			
			// print header
			writer.printHeader(header, outVariants);
			log.info("Processing a total of " + numberOfFiles + " clustered files.");
			while(numNotNull>0) {
				
				//gather reads next cluster
				ReadCluster nextCluster = new ReadCluster(numCluster);
				for(int i=0; i<numberOfFiles; i++) {
					
					//skip small clusters
					if(this.clusterSizes[numCluster] < minClusterDepth) {
//						System.out.println("Skipping cluster: " + numCluster);
						skipCluster(numCluster, iterators.get(i), currentReads, i);
					}
					
					//skip large clusters
					else if(this.clusterSizes[numCluster] > maxClusterDepth) {
//						System.out.println("Skipping cluster: " + numCluster);
						skipCluster(numCluster, iterators.get(i), currentReads, i);
					}
					
					else {
//						System.out.println("Adding reads to cluster: " + numCluster);
						addReadsToCluster(nextCluster, iterators.get(i), currentReads, i);
					}
					
					if(currentReads[i]==null) numNotNull--;
				}
				if(nextCluster.getNumberOfTotalReads()>0) {
					//Adding new task to the list and starting the new task
				    ProcessClusterVCFTask newTask = new ProcessClusterVCFTask(nextCluster, header, writer, this, outVariants, outConsensus);
				    newTask.setPairedEnd(filesDescriptor != null);
				    poolManager.queueTask(newTask);
				}
				if(numCluster%10000 == 0) {
					log.info("Processed cluster " + numCluster);
				}
					
				numCluster++;
			}
			
			
		} finally {
			for(FastqFileReader reader:readers) {
				if(reader!=null) reader.close();
			}
			poolManager.terminatePool();
			timer.cancel();
		}
	}
	
	private void skipCluster(Integer numCluster, Iterator<RawRead> iterator, 
			RawRead[] currentReads, int i) throws IOException {
		RawRead currentRead = currentReads[i];
		
		while(currentRead!=null) {
			String readIdWithCluster = currentRead.getName();
			String [] items = readIdWithCluster.split("\\"+READID_SEPARATOR);
			if(items.length < 2) {
				continue;
			}
			int currentReadCluster = Integer.parseInt(items[1]);
			// TODO rename reads without cluster info
			if (currentReadCluster>numCluster) break;
			else if (currentReadCluster<numCluster) throw new RuntimeException("Disorganized file. Current cluster: "+numCluster+" found: "+currentReadCluster+" in read. "+readIdWithCluster);
			if(iterator.hasNext()) {
				currentReads[i] = iterator.next();
				currentRead = currentReads[i];
				
			} else {
				log.info("Done with file " + i + ".");
				currentReads[i] = null;
				currentRead = null;				
			}
		}
	}
		
	
	private void addReadsToCluster(ReadCluster nextCluster, Iterator<RawRead> iterator, 
			RawRead[] currentReads, int i) throws IOException {
		
		RawRead currentRead = currentReads[i];
		int numCluster = nextCluster.getClusterNumber();
		while(currentRead!=null) {
			String readIdWithCluster = currentRead.getName();
			String [] items = readIdWithCluster.split("\\"+READID_SEPARATOR);
			if(items.length < 2) {
				continue;
			}
			String sampleId = items[0];
			int currentReadCluster = Integer.parseInt(items[1]);
			// TODO rename reads without cluster info
			if (currentReadCluster>numCluster) break;
			else if (currentReadCluster<numCluster) throw new RuntimeException("Disorganized file. Current cluster: "+numCluster+" found: "+currentReadCluster+" in read. "+readIdWithCluster);
//			System.out.println("From cluster: " + sampleId + "\tFrom read: " + currentRead.getName());
			nextCluster.addRead(currentRead, sampleId);
			if(iterator.hasNext()) {
				currentReads[i] = iterator.next();
				currentRead = currentReads[i];
				
			} else {
				log.info("Done with file " + i + ".");
				currentReads[i] = null;
				currentRead = null;				
			}
		}
	}

	private void printStatistics(String ref) throws IOException {
		try(PrintStream processStats = new PrintStream(outputPrefix+"_"+ref+"_processInfo.txt");){
			processStats.println("Process times:");
			processStats.print(this.processInfo.getTimes());
			processStats.println("Number of Files: " + Integer.toString(this.filenamesBySampleId1.size()));
			processStats.println("Number of Cluster Files: " + Integer.toString(this.numClusteredFiles));
			if(kmersMap != null) processStats.println("Number of Clusters: " + Integer.toString(kmersMap.size()));
			processStats.println("Number of Clusters with called variants: " + Integer.toString(this.numClustersWithCalledVariants));
			processStats.println("Number of Clusters with genotyped variants: " + Integer.toString(this.numClustersWithGenVariants));
			processStats.println("Number of Large Clusters (>"+maxClusterDepth+"): " + Integer.toString(this.numLargeClusters));
			processStats.println("Number of Small Clusters (<" + minClusterDepth + "): " + Integer.toString(this.numSmallClusters));
			processStats.println("Number of Reads: " + Integer.toString(this.numTotalReads));
			processStats.println("Number of Unclustered Reads I: " + Integer.toString(this.numUnclusteredReadsI));
			processStats.println("Number of Reads in Large Clusters: " + Integer.toString(this.numReadsLargeClusters));
			processStats.println("Number of Reads in Small Clusters: " + Integer.toString(this.numReadsSmallClusters));
		}
	}

	public void countVariants(List<VCFRecord> generatedRecords) {
		if(generatedRecords.size()>0) {
			numClustersWithCalledVariants++;
			//TODO: Calculate well
			numClustersWithGenVariants++;
		}
		
		
	}
}

class ProcessInfo {
	private List<Long> processTimes = new ArrayList<>(); 
	private List<String> processTimesLabels = new ArrayList<>();

	
	public void addTime(long time, String label) {
		processTimes.add(time);
		processTimesLabels.add(label);
	}
	
	public String getTimes() {
		String timeInfo = "";
		for(int i=0; i<processTimes.size(); i++) {
			timeInfo += processTimesLabels.get(i) + ": " + Long.toString(processTimes.get(i)) + "\n";
		}
		return timeInfo;
	}
}

class MemoryUsage extends TimerTask {
	
	private PrintStream stream;
	
	public MemoryUsage(PrintStream stream) {
		this.stream = stream;
	}
	
    public void run() {
       saveMemory(); 
    }
    
    private void saveMemory() {
    	long MB = 1024L * 1024L;
    	Runtime runtime = Runtime.getRuntime();
    	long memory = (runtime.totalMemory() - runtime.freeMemory()) / MB;
    	long time = System.currentTimeMillis();
    	stream.println(time + "\t" + memory);
    	
    }
    
}

class ClusteredReadsCache {
	private Map<Integer,List<RawRead>> clusteredReadsCache = new TreeMap<>();
	private Map<Integer, Map<String, String>> clusterInfo = new TreeMap<>();
	private int totalReads = 0;
	private List<String> outFiles = new ArrayList<>();
	
	public void addSingleRead(int k, RawRead read) {
		List<RawRead> readsClusterK = clusteredReadsCache.get(k);
		if(readsClusterK==null) {
			readsClusterK = new ArrayList<>();
			clusteredReadsCache.put(k, readsClusterK);
		}
		readsClusterK.add(read);
		totalReads++;
	}
	
	public void addClusterInfo(int k, String key, String value) {
		Map<String, String> info = clusterInfo.get(k);
		if(clusterInfo == null) {
			info = new HashMap<>();		
		}
		info.put(key, value);
	}
	
	public Map<String, String> getClusterInfo(int k) {
		return clusterInfo.get(k);
	}
	
	public List<String> getClusteredReadFiles() {
		return outFiles;
	}
	
	public List<RawRead> getClusterReads(int k) {
		return clusteredReadsCache.get(k);
	}
	
	/**
	 * @return the totalReads
	 */
	public int getTotalReads() {
		return totalReads;
	}
	
	/**
	 * Dumps the cache to the file with the given name and clears this cache
	 * @param outPrefix prefix of the file to dump the cache
	 */
	public void dump(String outPrefix) throws IOException {
		int number = outFiles.size();
		String singleFilename = outPrefix+"_clusteredReads_"+number+".fastq.gz";
		outFiles.add(singleFilename);
		try (OutputStream os = new FileOutputStream(singleFilename);
			 GZIPOutputStream gos = new GZIPOutputStream(os);
			 PrintStream out = new PrintStream(gos);) {
			for(List<RawRead> readsCluster:clusteredReadsCache.values()) {
				for(RawRead read:readsCluster) {
					read.save(out);
				}
			}
		}
		clusteredReadsCache.clear();
		totalReads = 0;
	}
}
