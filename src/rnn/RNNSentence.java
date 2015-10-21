package rnn;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Random;

import cn.fox.stanford.Tokenizer;
import common.Sentence;
import common.Tool;
import common.Util;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.util.PropertiesUtils;
import gnu.trove.TIntArrayList;
import gnu.trove.TObjectIntHashMap;

public class RNNSentence {

	public static void main(String[] args) throws Exception {
		FileInputStream fis = new FileInputStream(args[0]);
		Properties properties = new Properties();
		properties.load(fis);    
		fis.close();
		boolean debug = Boolean.parseBoolean(args[1]);
		
		String embedFile = PropertiesUtils.getString(properties, "embedFile", ""); 
		
		Parameters parameters = new Parameters(properties);
		parameters.printParameters();
		
		Tool tool = new Tool();
		tool.tokenizer = new Tokenizer(true, ' ');	
		
		/*
		 *  load all the positive and negative sentences.
		 *  For simpleness, we only take the first MAX_SENTENCE sentences in the positive and negative files
		 */
		List<Sentence> positiveSentences = new ArrayList<>();
		BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream("data/rt-polarity.pos"), "utf-8"));
		String thisLine = null;
		final int MAX_SENTENCE = 10000;
		int count = 0;
		while ((thisLine = br.readLine()) != null && count<MAX_SENTENCE) {
			if(thisLine.isEmpty()) {
				continue;
			} else {
				ArrayList<CoreLabel> tokens = tool.tokenizer.tokenize(0, thisLine);
				
				Sentence sent = new Sentence();
				sent.tokens = tokens;
				sent.polarity = 1;
				
				positiveSentences.add(sent);
				count++;
			}
		}
		br.close();
		
		List<Sentence> negativeSentences = new ArrayList<>();
		br = new BufferedReader(new InputStreamReader(new FileInputStream("data/rt-polarity.neg"), "utf-8"));
		thisLine = null;
		count = 0;
		while ((thisLine = br.readLine()) != null && count<MAX_SENTENCE) {
			if(thisLine.isEmpty()) {
				continue;
			} else {
				ArrayList<CoreLabel> tokens = tool.tokenizer.tokenize(0, thisLine);
				
				Sentence sent = new Sentence();
				sent.tokens = tokens;
				sent.polarity = 0;
				
				negativeSentences.add(sent);
				count++;
			}
		}
		br.close();
		
		// The last 10% sentences will be treated as the test set.
		int total = positiveSentences.size()+negativeSentences.size();
		int test = (int)(total*1.0/2*0.1);
		
		List<Sentence> testSet = new ArrayList<>();
		int index = 0;
		for(;index<test;index++) {
			testSet.add(positiveSentences.get(index));
			testSet.add(negativeSentences.get(index));
		}
		List<Sentence> trainSet = new ArrayList<>();
		for(;index<positiveSentences.size();index++) {
			trainSet.add(positiveSentences.get(index));
			trainSet.add(negativeSentences.get(index));
		}
		
		RNNSentence rnnSentence = new RNNSentence(parameters, debug);
		rnnSentence.trainAndTest(trainSet, testSet, embedFile);
		
	}
	
	public RNN rnn;
	public Parameters parameters;
	public List<String> knownWords;
	public TObjectIntHashMap<String> wordIDs;
	public boolean debug;

	public RNNSentence(Parameters parameters, boolean debug) {
		this.parameters = parameters;
		this.debug = debug;
	}
	
	public void trainAndTest(List<Sentence> trainSet, List<Sentence> testSet, String embedFile) throws Exception {
		List<String> word = new ArrayList<>();
		
		for(Sentence sent:trainSet) {
			for(CoreLabel token:sent.tokens) {
				word.add(token.word()); 
				
			}
		}
		
		knownWords = Util.generateDict(word, parameters.wordCutOff);
	    knownWords.add(0, Parameters.UNKNOWN);
	    wordIDs = new TObjectIntHashMap<String>();
	    int m = 0;
	    for (String w : knownWords)
	      wordIDs.put(w, (m++));
	    
	    System.out.println("#Word: " + knownWords.size());
	    
	    fillWordID(trainSet);
	    
	    double[][] E = new double[knownWords.size()][parameters.embeddingSize];
		Random random = new Random(System.currentTimeMillis());
		if(embedFile!=null && !embedFile.isEmpty()) { // try to load off-the-shelf embeddings
			TObjectIntHashMap<String> embedID = new TObjectIntHashMap<String>();
		    BufferedReader input = null;
		    
			  input = IOUtils.readerFromString(embedFile);
			  List<String> lines = new ArrayList<String>();
			  for (String s; (s = input.readLine()) != null; ) {
			    lines.add(s);
			  }
			
			  
			  String[] splits = lines.get(0).split("\\s+");
			  
			  int nWords = Integer.parseInt(splits[0]);
			  int dim = Integer.parseInt(splits[1]);
			  lines.remove(0);
			  double[][] embeddings = new double[nWords][dim];
			
			  
			  for (int i = 0; i < lines.size(); ++i) {
			    splits = lines.get(i).split("\\s+");
			    embedID.put(splits[0], i);
			    for (int j = 0; j < dim; ++j)
			      embeddings[i][j] = Double.parseDouble(splits[j + 1]);
			  }
			  
			  // using loaded embeddings to initial E
			  
			  for (int i = 0; i < E.length; ++i) {
			    int index = -1;
			    if (i < knownWords.size()) {
			      String str = knownWords.get(i);
			      //NOTE: exact match first, and then try lower case..
			      if (embedID.containsKey(str)) index = embedID.get(str);
			      else if (embedID.containsKey(str.toLowerCase())) index = embedID.get(str.toLowerCase());
			    }
			
			    if (index >= 0) {
			      for (int j = 0; j < E[0].length; ++j)
			        E[i][j] = embeddings[index][j];
			    } else {
			      for (int j = 0; j < E[0].length; ++j)
			        E[i][j] = random.nextDouble() * parameters.initRange * 2 - parameters.initRange;
			    }
			  }
			
		} else { // initialize E randomly
			System.out.println("No Embedding File, so initialize E randomly!");
			for(int i=0;i<E.length;i++) {
				for(int j=0;j<E[0].length;j++) {
					E[i][j] = random.nextDouble() * parameters.initRange * 2 - parameters.initRange;
				}
			}
		}
		
		RNN rnn = new RNN(parameters, debug, E);
		this.rnn = rnn;
		
				
		for (int iter = 0; iter < parameters.maxIter; ++iter) {
			
			rnn.process(trainSet);
			
			if (iter>0 && iter % 20 == 0)
				evaluate(testSet);
		}
		
		evaluate(testSet);
	}
	
	public double evaluate(List<Sentence> testSet)
			throws Exception {
		fillWordID(testSet);
        int correct = 0;
        for(Sentence sentence:testSet) {
        	
    		double[] Y = rnn.predict(sentence);
        	//double[] Y = rnn.predictWithMaxPooling(sentence);
    		
    		if(sentence.polarity==1 && Y[0]>Y[1])
    			correct++;
    		else if(sentence.polarity==0 && Y[0]<Y[1])
    			correct++;
        	
        }
        
        double rate = correct*1.0/testSet.size();
        System.out.println("correct rate: "+rate);
        return rate;
	}
	
	public void fillWordID(List<Sentence> dataSet) {
		for(Sentence sentence:dataSet) {
	    	TIntArrayList ids = new TIntArrayList();
			for(CoreLabel token:sentence.tokens) {
				ids.add(getWordID(token));
			}
			sentence.ids = ids;
	    }
	}
	
	public int getWordID(CoreLabel token) {
		return wordIDs.containsKey(token.word()) ? wordIDs.get(token.word()) : wordIDs.get(Parameters.UNKNOWN);
	}
	
	public int getWordID(String s) {
		
		return wordIDs.containsKey(s) ? wordIDs.get(s) : wordIDs.get(Parameters.UNKNOWN);
	}
}
