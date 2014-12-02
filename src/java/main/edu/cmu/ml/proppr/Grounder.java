package edu.cmu.ml.proppr;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.log4j.Logger;

import edu.cmu.ml.proppr.examples.GroundedExample;
import edu.cmu.ml.proppr.examples.InferenceExample;
import edu.cmu.ml.proppr.examples.InferenceExampleStreamer;
import edu.cmu.ml.proppr.examples.PosNegRWExample;
import edu.cmu.ml.proppr.prove.Prover;
import edu.cmu.ml.proppr.prove.wam.Goal;
import edu.cmu.ml.proppr.prove.wam.WamProgram;
import edu.cmu.ml.proppr.prove.wam.LogicProgramException;
import edu.cmu.ml.proppr.prove.wam.ProofGraph;
import edu.cmu.ml.proppr.prove.wam.Query;
import edu.cmu.ml.proppr.prove.wam.State;
import edu.cmu.ml.proppr.prove.wam.plugins.WamPlugin;
import edu.cmu.ml.proppr.util.Configuration;
import edu.cmu.ml.proppr.util.CustomConfiguration;
import edu.cmu.ml.proppr.util.Dictionary;
import edu.cmu.ml.proppr.util.multithreading.Multithreading;
import edu.cmu.ml.proppr.util.multithreading.Transformer;

/**
 * Exports a graph-based example for each raw example in a data file.
 * 
 * The conversion process is as follows:
 *     (read)-> raw example ->(thaw)-> thawed example ->(ground)-> grounded example
 * @author wcohen,krivard
 *
 */
public class Grounder {
	private static final Logger log = Logger.getLogger(Grounder.class);
	public static final String GROUNDED_SUFFIX = ".grounded";
	protected File graphKeyFile=null;
	protected Writer graphKeyWriter=null;
	protected GroundingStatistics statistics=null;

	protected Prover prover;
	protected WamProgram masterProgram;
	protected WamPlugin[] masterPlugins;
	protected int nthreads=1;
	protected int throttle=Multithreading.DEFAULT_THROTTLE;
	private int empty;

	public Grounder(Prover p, WamProgram program, WamPlugin ... plugins) {
		this.prover = p;
		this.masterProgram = program;
		this.masterPlugins = plugins;
	}
	public Grounder(int nthreads, int throttle, Prover p, WamProgram program, WamPlugin ... plugins) {
		this(p,program,plugins);
		this.nthreads = Math.max(1,nthreads);
		this.throttle = throttle;
	}

	public class GroundingStatistics {
		public GroundingStatistics() {
			log.info("Resetting grounding statistics...");
		}
		// statistics
		int totalPos=0, totalNeg=0, coveredPos=0, coveredNeg=0;
		InferenceExample worstX = null;
		double smallestFractionCovered = 1.0;
		int count;

		protected synchronized void updateStatistics(InferenceExample ex,int npos,int nneg,int covpos,int covneg) {
			// keep track of some statistics - synchronized for multithreading
			count ++;
			totalPos += npos;
			totalNeg += nneg;
			coveredPos += covpos;
			coveredNeg += covneg;
			double fractionCovered = covpos/(double)npos;
			if (fractionCovered < smallestFractionCovered) {
				worstX = ex;
				smallestFractionCovered = fractionCovered;
			}
		}
	}

	public void groundExamples(File dataFile, File groundedFile) {
		try {
			if (this.graphKeyFile != null) this.graphKeyWriter = new BufferedWriter(new FileWriter(this.graphKeyFile));
			this.statistics = new GroundingStatistics();
			this.empty = 0;

			Multithreading<InferenceExample,String> m = new Multithreading<InferenceExample,String>(log);

			m.executeJob(
					this.nthreads, 
					new InferenceExampleStreamer(dataFile).stream(), 
					new Transformer<InferenceExample,String>(){
						@Override
						public Callable<String> transformer(InferenceExample in, int id) {
							return new Ground(in,id);
						}}, 
					groundedFile, 
					this.throttle);
			if (empty>0) log.info("Skipped "+empty+" of "+this.statistics.count+" examples due to empty graphs");

			if (this.graphKeyFile != null) this.graphKeyWriter.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	long lastPrint = System.currentTimeMillis();

	/** Requires non-empty graph; non-empty example */
	protected String serializeGroundedExample(ProofGraph pg, GroundedExample x) {
		if (log.isInfoEnabled()) {
			long now = System.currentTimeMillis();
			if (now-lastPrint > 5000) {
				lastPrint = now;
			}
		}

		return pg.serialize(x);
	}

	//	protected String serializeGraphKey(InferenceExample ex, ProofGraph pg) {
	//		StringBuilder key = new StringBuilder();
	//		String s = ex.getQuery().toString();
	//		ArrayList<Object> states = pg.
	//		for (int i=1; i<states.size(); i++) {
	//			key.append(s)
	//			.append("\t")
	//			.append(i)
	//			.append("\t")
	//			.append((State) states.get(i))
	//			.append("\n");
	//		}
	//		return key.toString();
	//	}

	//	protected void saveGraphKey(InferenceExample rawX, GraphWriter writer) {
	//		try {
	//			this.graphKeyWriter.write(serializeGraphKey(rawX,writer));
	//		} catch (IOException e) {
	//			throw new IllegalStateException("Couldn't write to graph key file "+this.graphKeyFile.getName(),e);
	//		}
	//	}

	protected Prover getProver() {
		return this.prover;
	}


	/**
	 * Run the prover to convert a raw example to a random-walk example
	 * @param rawX
	 * @return
	 * @throws LogicProgramException 
	 */
	public GroundedExample groundExample(Prover p, ProofGraph pg) throws LogicProgramException {
		if (log.isTraceEnabled())
			log.trace("thawed example: "+pg.getExample().toString());
		Map<State,Double> ans = p.prove(pg);
		GroundedExample ground = pg.makeRWExample(ans);
		InferenceExample ex = pg.getExample();
		statistics.updateStatistics(ex,
				ex.getPosSet().length,ex.getNegSet().length,
				ground.getPosList().size(),ground.getNegList().size());
		//		if (this.graphKeyFile!= null) { saveGraphKey(rawX, writer); }
		return ground;
	}

	protected void reportStatistics(int empty) {
		if (empty>0) log.info("Skipped "+empty+" examples due to empty graphs");
		log.info("totalPos: " + statistics.totalPos 
				+ " totalNeg: "+statistics.totalNeg
				+" coveredPos: "+statistics.coveredPos
				+" coveredNeg: "+statistics.coveredNeg);
		if (statistics.totalPos>0) 
			log.info("For positive examples " + statistics.coveredPos 
					+ "/" + statistics.totalPos 
					+ " proveable [" + ((100.0*statistics.coveredPos)/statistics.totalPos) + "%]");
		if (statistics.totalNeg>0) 
			log.info("For negative examples " + statistics.coveredNeg 
					+ "/" + statistics.totalNeg 
					+ " proveable [" + ((100.0*statistics.coveredNeg)/statistics.totalNeg) + "%]");
		if (statistics.worstX!=null) 
			log.info("Example with fewest ["+100.0*statistics.smallestFractionCovered+"%] pos examples covered: "
					+ statistics.worstX.getQuery());
	}

	public static class ExampleGrounderConfiguration extends CustomConfiguration {
		private File keyFile;
		public ExampleGrounderConfiguration(String[] args, int inputFiles, int outputFiles, int constants, int modules) {
			super(args, inputFiles, outputFiles, constants, modules);
		}

		@Override
		protected void addCustomOptions(Options options, int[] flags) {
			options.addOption(OptionBuilder
					.withLongOpt("graphKey")
					.withArgName("keyFile")
					.hasArg()
					.withDescription("Save a key to the grounded graphs providing the LogicProgramState definitions of the numbered nodes")
					.create());
		}

		@Override
		protected void retrieveCustomSettings(CommandLine line, int[] flags,
				Options options) {
			if (line.hasOption("graphKey")) this.keyFile = new File(line.getOptionValue("graphKey"));
		}

		@Override
		public Object getCustomSetting(String name) {
			return keyFile;
		}
	}

	public void useGraphKeyFile(File keyFile) {
		log.info("Using graph key file "+keyFile.getName());
		this.graphKeyFile = keyFile;
	}

	///////////////////////////////// Multithreading scaffold //////////////////////////

	/** Transforms from inputs to outputs
	 * 
	 * @author "Kathryn Mazaitis <krivard@cs.cmu.edu>"
	 */
	private class Ground implements Callable<String> {
		InferenceExample inf;
		int id;
		public Ground(InferenceExample in, int id) {
			this.inf = in;
			this.id = id;
		}
		@Override
		public String call() throws Exception {
			ProofGraph pg = new ProofGraph(inf,masterProgram,masterPlugins);
			GroundedExample x = groundExample(getProver().copy(), pg);
			if (x.getGraph().edgeSize() > 0) {
				if (x.length() > 0) {
					return (serializeGroundedExample(pg, x));
				} else {
					log.warn("No positive or negative solutions for query "+id+":"+pg.getExample().getQuery().toString()+"; skipping");
				}
			}
			log.warn("Empty graph for example "+id);
			empty++;
			return null;
		}
	}

	/////////////////////////////////////// Command line ////////////////////////////////
	public static void main(String ... args) {
		int inputFiles = Configuration.USE_QUERIES;
		int outputFiles = Configuration.USE_GROUNDED;
		int constants = Configuration.USE_WAM | Configuration.USE_THREADS;
		int modules = Configuration.USE_GROUNDER | Configuration.USE_PROVER;
		
		ExampleGrounderConfiguration c = new ExampleGrounderConfiguration(args, inputFiles, outputFiles, constants, modules);

		long start = System.currentTimeMillis();
		if (c.getCustomSetting("graphKey") != null) c.grounder.useGraphKeyFile((File) c.getCustomSetting("graphKey"));
		c.grounder.groundExamples(c.queryFile, c.groundedFile);
		System.out.println("Time "+(System.currentTimeMillis()-start) + " msec");
		System.out.println("Done.");

	}
}
