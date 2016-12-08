package miningChanges;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;


import generics.Bug;
import generics.Commit;
import generics.Pair;
import main.Main;
import utils.FileToLines;
import utils.GitHelp;
import utils.ReadBugsFromXML;
import utils.WriteLinesToFile;

public class ProduceFileLevelResults {
	public String loc = main.Main.settings.get("workingLoc");
	private HashMap<Integer, HashSet<Integer>> bugRelatedFiles;
	public List<Bug> bugs;
	public HashMap<Integer, HashMap<String,Double>> hunkResults;
	public HashMap<String, Boolean> isCommitFix;
	public HashMap<Integer, HashMap<Integer,Double>> bugFixSuspicious;
	public List<String> hunkIndex;
	public HashMap<Integer, HashSet<String>> sourceCommits;
	public HashMap<String,Long> commitTime;
	public long startTime = Long.MAX_VALUE;
	
	public boolean loadOracles() {
		String filename = loc + File.separator + "bugSourceIndex.txt";
		
		File file = new File(filename);
		if (!file.exists()) {
			System.err.println("could not find file level oracles");
			return false;
		}
		List<String> lines = FileToLines.fileToLines(filename);
		int index = 0;
		bugs = ReadBugsFromXML.getFixedBugsFromXML(main.Main.settings.get("bugReport"));
		bugRelatedFiles = new HashMap<Integer,HashSet<Integer>>();
		while (index < lines.size()) {
//			System.out.println(lines.get(index));
			String[] splits = lines.get(index).split("\t");
			int bid = Integer.parseInt(splits[0]);
			HashSet<Integer> revisions = new HashSet<Integer>();
			for (int i = 1; i < splits.length; i++)
				revisions.add(Integer.parseInt(splits[i]));
			bugRelatedFiles.put(bid, revisions);
			index++;
		}
		
		SimpleDateFormat formatter = new SimpleDateFormat("EEE MMM dd HH:mm:ss yyyy Z");
		commitTime = new HashMap<String, Long>();
		filename = loc + File.separator + "logOneline.txt";
		lines = FileToLines.fileToLines(filename);
		try {
			for (String line : lines) {
				String[] splits = line.split("\t");
				Date date = formatter.parse(splits[2]);
				commitTime.put(splits[0], date.getTime());
				if (date.getTime() < startTime)
					startTime = date.getTime();
			}
		} catch (Exception e) {
			System.out.println("Parsing time error");
			e.printStackTrace();
		}
		
		return true;
	}
	
	public void loadResults() {
		String resultFile = loc + File.separator + "results_file" + ".txt";
		File file = new File(resultFile);
		if (!file.exists()) {
			ObtainVSMScore ovs = new ObtainVSMScore();
			hunkResults = ovs.obtainSimilarity(false);
		} else {
			System.out.println("Results of file level exists, read from results_file.txt");
			hunkResults = new HashMap<>();
			List<String> lines = FileToLines.fileToLines(resultFile);
			for (String line : lines) {
				String[] splits = line.split("\t");
				int sid = Integer.parseInt(splits[0]);
				hunkResults.put(sid, new HashMap<>());
				for (int i = 1; i < splits.length; i++)
					hunkResults.get(sid).put(splits[i].split(":")[0], Double.parseDouble(splits[i].split(":")[1]));
			}
		}
		
	}
	
	public void loadFileSuspiciousScore() throws Exception {
		String commitFix = loc + File.separator + "isCommitFix.txt";
		File file = new File(commitFix);
		isCommitFix = new HashMap<String,Boolean>();
		List<String> lines = null;
		if (!file.exists()) {
			String logFullDescription = loc + File.separator + "logFullDescription.txt";
			String content = GitHelp.getAllCommitWithFullDescription(main.Main.settings.get("repoDir"));
			WriteLinesToFile.writeToFiles(content, logFullDescription);
			List<Commit> commits = GitHelp.readFromTextGIT(logFullDescription);
			lines = new ArrayList<String>();
			for (int i = 0; i < commits.size(); i++) {
				String hash = commits.get(i).changeSet;
				String description = commits.get(i).description;
				description = description.toLowerCase();
				if (description.contains("bug") || description.contains("patch") || 
						description.contains("fix") || description.contains("issue")) {
//				if (description.contains("fix") || description.contains("bug")) {
					isCommitFix.put(hash.substring(0, 7), true);
					lines.add(hash + "\t1");
				} else {
					isCommitFix.put(hash.substring(0, 7), false);
					lines.add(hash + "\t0");
				}
			}
			WriteLinesToFile.writeLinesToFile(lines, commitFix);
		} else {
			lines = FileToLines.fileToLines(commitFix);
			for (String line : lines) {
				String[] split = line.split("\t");
				isCommitFix.put(split[0].substring(0,7), split[1].equals("1"));
			}
		}	
		
		String filename = loc + File.separator + "hunkIndex.txt";
		hunkIndex = FileToLines.fileToLines(filename);


		filename = loc + File.separator + "sourceHunkLink.txt";
		lines = FileToLines.fileToLines(filename);
		
		sourceCommits = new HashMap<Integer,HashSet<String>>();
		for (String line : lines) {
			String[] tmp = line.split("\t");
			int sid = Integer.parseInt(tmp[0]);
			for (int i = 1; i < tmp.length; i++) {
				int hid = Integer.parseInt(tmp[i]);
				String hunk = hunkIndex.get(hid);
				String commit = hunk.split("@")[0];
				if (!sourceCommits.containsKey(sid)) sourceCommits.put(sid, new HashSet<String>());
				sourceCommits.get(sid).add(commit);
			}
		}
		
		bugFixSuspicious = new HashMap<Integer,HashMap<Integer, Double>>();
		for (Bug bug : bugs) {
			long time = bug.reportTime;
			bugFixSuspicious.put(bug.id, new HashMap<Integer,Double>());
			for (int sid : sourceCommits.keySet()) {
				HashSet<String> commits = sourceCommits.get(sid);
				double score = 0;
				for (String commit : commits) {
					if (time > commitTime.get(commit)) {
						if (!isCommitFix.containsKey(commit))
							continue;
						if (isCommitFix.get(commit)) {
							double norm = (commitTime.get(commit) - startTime) * 1.0 / (time - startTime);
							double sus = 1.0 / (1 + Math.exp(-12 * norm + 12)); 
//							System.out.println(sid + "\t" + norm + "\t" + sus);
							score += sus;
						}
					}
				}
				bugFixSuspicious.get(bug.id).put(sid, score);
			}
		}
	}
	
	public void integrateResults() {
		List<List<Integer>> ranks = new ArrayList<List<Integer>>();
		List<String> resultsLines = new ArrayList<String>();
		double belta1 = Main.belta1;
		if (loc.toLowerCase().contains("zxing"))
			belta1 = 0.05;
		for (Bug bug : bugs) {
			int bid = bug.id;
			if (!hunkResults.containsKey(bid)) continue;
			HashMap<String, Double> results = hunkResults.get(bid);			
			HashMap<Integer,Double> finalResults = new HashMap<Integer, Double>();
			for (String change : results.keySet()) {
				
				int sid = Integer.parseInt(change);
				if (bugFixSuspicious.get(bug.id).containsKey(sid))
					finalResults.put(sid, results.get(change) + belta1 * bugFixSuspicious.get(bid).get(sid));
				else finalResults.put(sid, results.get(change));
			}
			
			List<Pair<Integer, Double>> finalRanks = new ArrayList<Pair<Integer,Double>>();
			for (int sid : finalResults.keySet()) {
				finalRanks.add(new Pair<Integer,Double>(sid, finalResults.get(sid)));
			}
			
			Collections.sort(finalRanks);
			List<Integer> rank = new ArrayList<Integer>();
			
			for (int i = 0; i < finalRanks.size(); i++) {
				int index = finalRanks.size() - i - 1;
				if (bugRelatedFiles.get(bid).contains(finalRanks.get(index).getKey()))
					rank.add(i);
			}
			ranks.add(rank);
			System.out.println(bid + "\t" + rank.toString());
		}
		
		int N = 10;
		double[] topN = EvaluationMetric.topN(ranks, N);
		double map = EvaluationMetric.MAP(ranks);
		double mrr = EvaluationMetric.MRR(ranks);
		resultsLines.add("map:\t" + map);
		resultsLines.add("mrr:\t" + mrr);
		
		System.out.println(map + "\t" + mrr);
		for (int i = 0; i < N; i++) {
			System.out.print(topN[i] + "\t");
			resultsLines.add("top@" + (i + 1) + "\t" + topN[i]);
		}
		System.out.println();
		String filename = main.Main.settings.get("workingLoc") + File.separator + "fileLevelResults.txt";
		WriteLinesToFile.writeLinesToFile(resultsLines, filename);
	}

	public void getFinalResults() throws Exception {
		if (loadOracles()) {
			System.out.println("Finish loading files");
			loadResults();
			System.out.println("Finish calculating suspicious score");
			loadFileSuspiciousScore();
			integrateResults();
		}
	}
}
