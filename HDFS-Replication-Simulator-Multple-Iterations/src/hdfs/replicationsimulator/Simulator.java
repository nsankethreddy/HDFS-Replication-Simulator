package hdfs.replicationsimulator;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/*
 * Object used to Initialize the system. Create all the objects and processes.
 */
public class Simulator {

	private static Namenode namenode;
	private static AllDatanode allDatanodes;

	private static Queue<Event> toNamenode;
	private static Queue<Event> toDatanodes;

	private static List<SimTrace> traceList;

	/*
	 * Settings (defaults)
	 */
	private static int numberofBlocks = 75000;
	private static int numberofReplicasHot = 3;
	private static int numberofReplicasCold = 2;
	private static int numberofDatanodes = 10000;
	private static int dataNodeCapacity = 3200; //3200 Gives 400 GB
	private static int bandwidth = 1024;
	private static int heartbeat = 1000;
	private static int timeout = 3;
	private static int blockSize = 64;
	private static int numberofSSDs = 64;
	private static int threshold = 5;
	private static int totalPower = 0;
	private static int blockPercentage = 50; //Percentage of blocks to go cold
	private static int requiredNumberOfSsd = 3;
	private static List<Event> simulationFailureEvents;

	public static void init(String configFile) {
		// Read the test.txt file: CHANGE THE NAME!
		try {
			FileReader fr = new FileReader(configFile);
			BufferedReader in = new BufferedReader(fr);
			String data;

			int lastFailureTime = 0;
			int lastFailingNode = 0;
			int fraction = 50;
			boolean timeRetrieved = false;
			boolean nodeRetrieved = false;

			simulationFailureEvents = new ArrayList<Event>();
			traceList = new ArrayList<SimTrace>();

			while ((data = in.readLine()) != null) {

				if (data.contains("replica_hot_zone=")) {
					numberofReplicasHot = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("replica_cold_zone=")) {
					numberofReplicasCold = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("nodes=")) {
					numberofDatanodes = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("bw=")) {
					bandwidth = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("threshold=")) {
					threshold = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("heartbeat=")) {
					heartbeat = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("timeout=")) {
					timeout = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("block=")) {
					blockSize = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("Simulated_percentage_of_data_going_cold=")) {
					blockPercentage = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("nBlocks=")) {
					numberofBlocks = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("HZpercent")) {
					fraction = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("dn_capacity=")) {
					dataNodeCapacity = Integer.parseInt(data.split("=")[1]);
				} else if (data.contains("failure_time=") && !timeRetrieved) {
					lastFailureTime = Integer.parseInt(data.split("=")[1]);
					timeRetrieved = true;
				} else if (data.contains("failing_node_id=") && !nodeRetrieved) {
					lastFailingNode = Integer.parseInt(data.split("=")[1]);
					nodeRetrieved = true;
				}

				// Fill the list of events
				if (timeRetrieved && nodeRetrieved) {
					//simulationFailureEvents.add(new Event(lastFailingNode,
						//	Event.FAILURE, lastFailureTime));
					timeRetrieved = false;
					nodeRetrieved = false;
				}
				numberofSSDs = (fraction * numberofDatanodes)/100;
			}
			requiredNumberOfSsd = (int) Math.ceil((double)numberofBlocks/ dataNodeCapacity * numberofReplicasHot);
			//System.out.println("Number " + numberofSSDs + "\nRequired " + requiredNumberOfSsd);
			if(requiredNumberOfSsd > numberofSSDs && numberofSSDs != 0) {
				System.out.println("Cannot Simulate. Blocks exceed Hot Zone capacity.");
				System.exit(1);

			}


		} catch (IOException e) {
			e.printStackTrace();
		}
		toNamenode = new ConcurrentLinkedQueue<Event>();
		toDatanodes = new ConcurrentLinkedQueue<Event>();

		// Create the Namenode
		initializeNamenode();
		// Create the datanode distribution and fill the Datanode map
		initializeDatanodes();
		// Create the blocks distribution, attribute them to datanodes and fill
		// the blocksMap
		initializeBlocks();

	}

	public static int getCapacity() {
		return dataNodeCapacity;
	}

	private static void startFailure() {
		
		Thread killer = new Thread(new NodeKiller());
		killer.start();
		
	}

	private static void initializeNamenode() {
		namenode = new Namenode();
		System.out.print("Namenode Created.\n");
	}

	private static void initializeDatanodes() {

		// Create all the datanodes
		Power power = new Power();
		allDatanodes = new AllDatanode();
		//int numberofSSDs = numberofDatanodes/3;
		if(numberofSSDs == 0) {
			//power.totalPower += power.hddActive * numberofDatanodes;
			for (int i = numberofSSDs; i < numberofDatanodes; i++) {
				allDatanodes.addNode(new Datanode(i, dataNodeCapacity, 0));

				DatanodeInfo datanodeInfo = new DatanodeInfo(i, dataNodeCapacity, 0);
				namenode.addNode(datanodeInfo);
				//System.out.println("Created HDD with ID " + i);
				power.totalPower += power.bootHdd;

			}

			for (int  i = 0; i < numberofDatanodes; i++) {
				Datanode dn = allDatanodes.getNode(i);
				dn.setState(1);
			}
		}
		else {
			for (int i = 0; i < numberofSSDs; i++) {
				allDatanodes.addNode(new Datanode(i, dataNodeCapacity, 1));

				DatanodeInfo datanodeInfo = new DatanodeInfo(i, dataNodeCapacity, 1);
				namenode.addNode(datanodeInfo);
				//System.out.println("Created SSD with ID " + i);
				//power.totalPower += power.ssdActive;
			}


			power.totalPower += requiredNumberOfSsd * power.bootSsd;
			power.totalPower += (numberofSSDs - requiredNumberOfSsd) * (power.bootSsd + power.ssdSleep);

			for (int i = numberofSSDs; i < numberofDatanodes; i++) {
				allDatanodes.addNode(new Datanode(i, dataNodeCapacity, 0));

				DatanodeInfo datanodeInfo = new DatanodeInfo(i, dataNodeCapacity, 0);
				namenode.addNode(datanodeInfo);
				//System.out.println("Created HDD with ID " + i);
				power.totalPower += power.bootHdd;

			}

			for (int  i = 0; i < requiredNumberOfSsd; i++) {
				Datanode dn = allDatanodes.getNode(i);
				dn.setState(1);
			}

			for (int  i = requiredNumberOfSsd; i < numberofSSDs; i++) {
				Datanode dn = allDatanodes.getNode(i);
				dn.setState(0);
			}

			for (int  i = numberofSSDs; i < numberofDatanodes; i++) {
				Datanode dn = allDatanodes.getNode(i);
				dn.setState(0);
			}


		}

		System.out.println(numberofSSDs + " SSD Datanodes Created.");
		System.out.println(numberofDatanodes - numberofSSDs + " HDD Datanodes Created.");

		if(numberofSSDs != 0 ) { System.out.println(requiredNumberOfSsd + " SSD Datanodes Active.\n"); }
		else {System.out.println("\n");}

		System.out.println(power.totalPower + " Watts of Power consumed for bringing up " + numberofDatanodes + " Nodes");
		totalPower += power.totalPower;

	}

	public static int getTotalPower() {
		int x = totalPower;
		totalPower = 0 ;
		return x;
	}

	public static long getTotalFreeSpace() {
		long totalSpace = 0;
		if(numberofSSDs == 0) {
			for (int  i = 0; i < numberofDatanodes; i++) {
				Datanode dn = allDatanodes.getNode(i);
				totalSpace += dn.getFreeSpace();
			}
		}
		else {
			for (int  i = 0; i < numberofSSDs; i++) {
				Datanode dn = allDatanodes.getNode(i);
				totalSpace += dn.getFreeSpace();
			}
		}
		return totalSpace;
	}

	private static void initializeBlocks() {
		int currentDN = 0;
		Power power = new Power();
		for (int i = 0; i < numberofBlocks; i++) {
			// Create the Block
			Block block = new BlockInfo(i);

			// Add the block to the namenode index
			namenode.addBlock((BlockInfo)block);

			//System.out.println("Block Number = " + i);
			/* THIS VERSION USE RANDOM AND SO IS NOT GOOD.
			// Adds the block randomly to a node
			 * Random r = new Random();
			for (int j = 0; j < numberofReplicas; j++) {
				int idDatanode = r.nextInt(numberofDatanodes - 1) ;
				//int index = idDatanode - 1;
				Datanode dn = allDatanodes.getNode(idDatanode);//TODO
				dn.addBlock(block);
				namenode.initAddBlock(idDatanode, (BlockInfo)block);
			}
			*/
			//adds the blocks to a node (chained mode)

			for (int j = 0; j < numberofReplicasHot; j++) {
				int idDatanode = currentDN;
				//int index = idDatanode - 1;

				boolean blockAdded = false;
				if(getTotalFreeSpace() == 0) {
					System.out.println("No free space in cluster. Cannot add block");
					System.exit(1);
				}
				while(!blockAdded) {
					//System.out.println("Current Datanode is " + currentDN);
					Datanode dn = allDatanodes.getNode(currentDN);
					//System.out.println("Attempting to add block to node " + dn.getId() + "with space " + dn.getFreeSpace());
					if(dn.getFreeSpace() != 0) {
						dn.addBlock(block);
						if(dn.getType() == 1 && dn.getState()) {
							power.totalPower += power.writeSsd;
							power.totalPower += power.ssdActive;
						}
						else {
							power.totalPower += power.writeHdd;
							power.totalPower += power.hddActive;
						}
						namenode.initAddBlock(currentDN, (BlockInfo)block);
						blockAdded = true;
					}

					if(numberofSSDs == 0){
						currentDN = (currentDN == numberofDatanodes-1)? 0: currentDN+1; // Initialize sequentially across both SSD and HDD

					}
					else {
						currentDN = (currentDN == requiredNumberOfSsd-1)? 0: currentDN+1; // Initialize all blocks to SSD
					}
				}
				//currentDN = (currentDN == numberofDatanodes-1)? 0: currentDN+1; // Initialize sequentially across both SSD and HDD
			}
		}
		//System.out.print(numberofBlocks + " Blocks distributed\n\n\n");
		System.out.print(power.totalPower + " Watts of Power consumed for initial block distribution (First write)\n");
		totalPower += power.totalPower;
	}

	public static void start() {
		allDatanodes.start();
		namenode.start();
		startFailure();
	}

	public static int getBandwidth() {
		return bandwidth;
	}

	public static int getHeartbeat() {
		return heartbeat;
	}

	public static int getTimeout() {
		return timeout;
	}

	public static int getBlockSize() {
		return blockSize;
	}
	
	public static int getNumberofReplicas() {
		return numberofReplicasHot;
	}

	public static Queue<Event> getToNamenode() {
		return toNamenode;
	}

	public static Queue<Event> getToDatanodes() {
		return toDatanodes;
	}

	public static Namenode getNamenode() {
		return namenode;
	}

	public static void addTrace(SimTrace st) {
		traceList.add(st);
		System.out.println(st.toString());
	}

	public static void printResults() {

		File f = new File("results.log");
		try {
			FileWriter fw = new FileWriter(f);
			int dataLosses = 0;

			List<Long> ttrs = new ArrayList<Long>();
			Map<Integer, Long> failureEvents = new HashMap<Integer, Long>();
			for (SimTrace st : traceList) {
				fw.write(st.toString());
				if (st.isLossEvent()) {
					dataLosses++;
				}

				if (st.getAction() == SimTrace.FAILURE_DETECTION) {
					failureEvents.put(st.getId(), st.getTimestamp());
				} else if (st.getAction() == SimTrace.BLOCK_RECEIVED) {
					long failTime = 0;
					try {
						failTime = failureEvents.get(st.getIdBlock());
						long ttr = st.getTimestamp() - failTime;
						ttrs.add(ttr);
					}
					catch (NullPointerException e) {
						System.out.println("Block no "+st.getIdBlock()+"hasn't failed");
					}
					
				}
			}
			fw.write(processMTTR(ttrs));
			fw.write(processDurability(dataLosses));
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}

	}

	private static String processDurability(int losses) {
		double durability = 1 - (losses / numberofBlocks);
		return "DURABILITY: " + durability;
	}

	private static String processMTTR(List<Long> ttrs) {

		double sum = 0;
		for (Long l : ttrs) {
			sum += l;
		}
		double mttr;
		if(ttrs.size()!=0)
			mttr = sum / ttrs.size();
		else
			mttr=0;
		return "MTTR: " + mttr;
	}

	public static List<Event> getSimulationFailureEvents() {
		return simulationFailureEvents;
	}

	public static AllDatanode getAllDatanodes() {
		return allDatanodes;
	}

	public static void printTimesAccessed() {
		for (int i = 0; i < numberofDatanodes; i++) {
			Datanode dn = allDatanodes.getNode(i);
			List<Block> blocks = dn.getBlocks();
			System.out.println("Datanode : " + i);
			for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
				Block block = blocks.get(blockIndex);
				System.out.println("Number of times block accessed. Block id : " + block.getId() + " : " + block.getTimesAccessed());

			}
		}
	}

	public static void printLastAccessed() {
		for (int i = 0; i < numberofDatanodes; i++) {
			Datanode dn = allDatanodes.getNode(i);
			List<Block> blocks = dn.getBlocks();
			System.out.println("Datanode : " + i);
			for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
				Block block = blocks.get(blockIndex);
				System.out.println("Time since last access. Block id : " + block.getId() + " : " + block.getLastAccessed());

			}
		}
	}

	public static void makeCold() {

		Power power = new Power();
		Power power2 = new Power();
		//int blockPercentage = 50; //percentage of blocks to turn cold


		for(int i = 0; i < numberofDatanodes; i++){
			Datanode dn = allDatanodes.getNode(i);

//			if(dn.getType() == 1 && !dn.getState()) {
//				power2.totalPower += power2.ssdSleep;
//			}
//			else if(dn.getType() == 1 && dn.getState()) {
//				power2.totalPower += power2.ssdActive;
//			}
//			else if(dn.getType() != 1 && dn.getState()) {
//				power2.totalPower += power2.hddActive;
//			}
//			else {
//				power2.totalPower += power2.hddSleep;
//			}

			List<Block> blocks = dn.getBlocks();
			int blockPercent =(int) Math.ceil((double) (blocks.size() * blockPercentage)/100);
			for(int blockIndex = 0; blockIndex < blocks.size(); blockIndex++ , blockPercent--) {

				Block block = blocks.get(blockIndex);

				if(blockPercent > 0) {
					for (int j = 0; j < numberofDatanodes; j++) {
						Datanode dn2 = allDatanodes.getNode(j);
						List<Block> blocks2 = dn2.getBlocks();

						for (int blockIndex2 = 0; blockIndex2 < blocks2.size(); blockIndex2++) {
							Block block2 = blocks2.get(blockIndex2);
							if (block.getId() == block2.getId()) {
								block2.changeLastAccess();
								/*if(dn2.getType() == 1 && dn2.getState()) {
									power2.totalPower += power2.ssdActive;
								}
								else {
									if(numberofSSDs == 0) {
										power2.totalPower += power2.hddActive;
									}
									else{
										power2.totalPower += power2.hddSleep;
									}

								}*/
							}
						}
					}
				}
				else {

					for(int j = 0; j < numberofDatanodes; j++) {
						Datanode dn2 = allDatanodes.getNode(j);
						List<Block> blocks2 = dn2.getBlocks();

						for(int blockIndex2 = 0; blockIndex2 < blocks2.size(); blockIndex2++) {
							Block block2 = blocks2.get(blockIndex2);
							if(block.getId() == block2.getId()){
								if(dn2.getType() == 1){
									power.totalPower += power.readSsd;
								}
								else {
									power.totalPower += power.readHdd;
								}

								block2.access();
							}
						}
					}
				}
			}
		}

		System.out.print(power.totalPower + " Watts of Power consumed for initial Block access (First Read of data)\n");
		measureClusterPower();
//		Power power1 = new Power();
//		power1.totalPower += requiredNumberOfSsd * power1.ssdActive;
//		power1.totalPower += (numberofSSDs - requiredNumberOfSsd) * power1.ssdSleep;
//		power1.totalPower += (numberofDatanodes - numberofSSDs) * power1.hddSleep;
//		System.out.println(power1.totalPower + " Watts of Power consumed for maintaining the infrastructure (Cluster in active state)");
//		//		System.out.print(power2.totalPower + " Watts of Power consumed for maintaining the infrastructure (Cluster in active state)\n");
		totalPower += power.totalPower;
//		totalPower += power1.totalPower;
	}

	public static void moveBlocks(){
		if(numberofSSDs != numberofDatanodes) {
			//int threshold = 3;
			Power power = new Power();
			int currentDN = numberofSSDs;
			List<Integer> idlist = new ArrayList<>();

			//Get list of blocks that are cold
			for(int i = 0; i < numberofSSDs; i++) {
				Datanode dn = allDatanodes.getNode(i);
				List<Block> blocks = dn.getBlocks();

				for(int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
					Block block = blocks.get(blockIndex);

					int id = block.getId();
					boolean flag = true;

					Iterator itr = idlist.iterator();
					while (itr.hasNext())
					{
						int x = (Integer)itr.next();
						if (x == id)
							flag = false;
					}

					if(block.getLastAccessed() > threshold & flag) {
						idlist.add(id);
					}
				}
			}

			//Remove list of blocks from hot zone
			for (int i = 0; i < numberofSSDs; i++) {
				Datanode dn = allDatanodes.getNode(i);
				Iterator itr = idlist.iterator();
				while (itr.hasNext())
				{
					int id = (Integer)itr.next();
					dn.removeBlock(id);
				}
			}

			//Duplicate blocks within cold zone
			for (int i = 0; i < idlist.size(); i++) {
				Block block = new BlockInfo(idlist.get(i));

				for (int j = 0; j < numberofReplicasCold; j++) {
					int idDatanode = currentDN;
					Datanode dn = allDatanodes.getNode(idDatanode);
					dn.addBlock(block);
					if(dn.getType() == 1) {
						power.totalPower += power.writeSsd;
						power.totalPower += power.ssdActive;
					}
					else {
						power.totalPower += power.writeHdd;
						power.totalPower += power.hddActive;
					}
					namenode.initAddBlock(idDatanode, (BlockInfo)block);
					if(numberofSSDs == 0){
						currentDN = (currentDN == numberofDatanodes-1)? 0: currentDN+1;

					}
					else {
						currentDN = (currentDN == numberofDatanodes-1)? numberofSSDs: currentDN+1;
					}
				}
			}

			System.out.println(power.totalPower + " Watts of Power consumed when transferring data to cold zone");
			totalPower += power.totalPower;

			//Balance hot zone here

		}

	}

	public static void accessData(){
		Power power = new Power();
		if(numberofSSDs != 0) {
			for(int i = 0; i < requiredNumberOfSsd; i++){
				Datanode dn = allDatanodes.getNode(i);
				List<Block> blocks = dn.getBlocks();
				for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++){
					Block block = blocks.get(blockIndex);
					block.access();
					power.totalPower += power.readSsd;
					power.totalPower += power.ssdActive;
				}
			}

			int percent = (5 * numberofBlocks) / 100;
			for(int i = numberofSSDs; (i < numberofDatanodes) && (percent != 0); i++) {
				Datanode dn = allDatanodes.getNode(i);
				List<Block> blocks = dn.getBlocks();
				for(int blockIndex = 0; (blockIndex < blocks.size()) && (percent != 0); blockIndex ++, percent--) {
					Block block = blocks.get(blockIndex);
					block.access();
					power.totalPower += power.readHdd;
					power.totalPower += power.hddActive;
					power.totalPower += power.hddTransition;
				}
			}

			for(int i = numberofSSDs; i < numberofDatanodes; i++) {
				power.totalPower += power.hddSleep;
			}
			for(int i = requiredNumberOfSsd; i < numberofSSDs; i++ ) {
				power.totalPower += power.ssdSleep;
			}
		}
		else {
			for(int i = 0; i < numberofDatanodes; i++){
				Datanode dn = allDatanodes.getNode(i);
				List<Block> blocks = dn.getBlocks();
				for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++){
					Block block = blocks.get(blockIndex);
					block.access();
					power.totalPower += power.readHdd;
					power.totalPower += power.hddActive;
				}
			}

		}
		System.out.println(power.totalPower + " Watts of Power consumed for continued Block access (Regular reading of data)");
		totalPower += power.totalPower;

	}

	public static void balance() {
		Power power = new Power();
		if(numberofSSDs != 0) {
			List<Integer> idlist = new ArrayList<>();
			int currentDN = 0;

			//Get list of blocks in hot zone
			for(int i = 0; i < requiredNumberOfSsd; i++) {
				Datanode dn = allDatanodes.getNode(i);
				List<Block> blocks = dn.getBlocks();
				for(int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
					Block block = blocks.get(blockIndex);

					int id = block.getId();
					boolean flag = true;

					Iterator itr = idlist.iterator();
					while (itr.hasNext())
					{
						int x = (Integer)itr.next();
						if (x == id)
							flag = false;
					}

					if(flag) {
						idlist.add(id);
					}
				}
			}

			int tempRequiredNumberOfSsd = (int) Math.ceil((double)idlist.size()/ dataNodeCapacity) * numberofReplicasHot;

			if(tempRequiredNumberOfSsd != requiredNumberOfSsd) {
				requiredNumberOfSsd = tempRequiredNumberOfSsd;
				//Delete all blocks from hot zone
				for (int i = 0; i < requiredNumberOfSsd; i++) {
					Datanode dn = allDatanodes.getNode(i);
					Iterator itr = idlist.iterator();
					while (itr.hasNext())
					{
						int id = (Integer)itr.next();
						dn.removeBlock(id);
					}
				}

				//Distibute blocks across requiredSSDs
				for (int i = 0; i < idlist.size(); i++) {
					Block block = new BlockInfo(idlist.get(i));

					for (int j = 0; j < numberofReplicasHot; j++) {
						int idDatanode = currentDN;
						Datanode dn = allDatanodes.getNode(idDatanode);
						dn.addBlock(block);
						if(dn.getType() == 1) {
							power.totalPower += power.readSsd;
							power.totalPower += power.writeSsd;
						}
						else {
							power.totalPower += power.readHdd;
							power.totalPower += power.writeHdd;
						}
						namenode.initAddBlock(idDatanode, (BlockInfo)block);
						if(numberofSSDs == 0){
							currentDN = (currentDN == numberofDatanodes-1)? 0: currentDN+1;

						}
						else {
							currentDN = (currentDN == requiredNumberOfSsd-1)? 0: currentDN+1;
						}
					}
				}

				for (int i = requiredNumberOfSsd; i < numberofSSDs; i++) {
					Datanode dn = allDatanodes.getNode(i);
					dn.setState(0);
				}
			}

		}
		else {
			List<Integer> idlist = new ArrayList<>();
			int currentDN = 0;

			for(int i = 0; i < numberofDatanodes; i++) {
				Datanode dn = allDatanodes.getNode(i);
				List<Block> blocks = dn.getBlocks();
				for(int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
					Block block = blocks.get(blockIndex);

					int id = block.getId();
					boolean flag = true;

					Iterator itr = idlist.iterator();
					while (itr.hasNext())
					{
						int x = (Integer)itr.next();
						if (x == id)
							flag = false;
					}

					if(flag) {
						idlist.add(id);
					}
				}
			}

			for (int i = 0; i < numberofDatanodes; i++) {
				Datanode dn = allDatanodes.getNode(i);
				Iterator itr = idlist.iterator();
				while (itr.hasNext())
				{
					int id = (Integer)itr.next();
					dn.removeBlock(id);
				}
			}

			for (int i = 0; i < idlist.size(); i++) {
				Block block = new BlockInfo(idlist.get(i));

				for (int j = 0; j < numberofReplicasCold; j++) {
					int idDatanode = currentDN;
					Datanode dn = allDatanodes.getNode(idDatanode);
					dn.addBlock(block);
					if(dn.getType() == 1) {
						power.totalPower += power.readSsd;
						power.totalPower += power.writeSsd;
					}
					else {
						power.totalPower += power.readHdd;
						power.totalPower += power.writeHdd;
					}
					namenode.initAddBlock(idDatanode, (BlockInfo)block);
					if(numberofSSDs == 0){
						currentDN = (currentDN == numberofDatanodes-1)? 0: currentDN+1;

					}
					else {
						currentDN = (currentDN == requiredNumberOfSsd-1)? 0: currentDN+1;
					}
				}
			}


		}


		System.out.println(power.totalPower + " Watts of Power consumed for block balancing");
		totalPower += power.totalPower;
		measureClusterPower();
	}

	public static void measureClusterPower() {
		Power power1 = new Power();
		power1.totalPower += requiredNumberOfSsd * power1.ssdActive;
		power1.totalPower += (numberofSSDs - requiredNumberOfSsd) * power1.ssdSleep;
		power1.totalPower += (numberofDatanodes - numberofSSDs) * power1.hddSleep;
		System.out.println(power1.totalPower + " Watts of Power consumed for maintaining the infrastructure (Cluster in active state)");
		totalPower += power1.totalPower;
	}

}

