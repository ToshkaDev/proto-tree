package serviceimpl;

import java.util.*;

import biojobs.BioJobDao;
import biojobs.BioJobResultDao;
import enums.Status;
import enums.ParamPrefixes;
import model.internal.ProtoTreeInternal;
import model.request.ProtoTreeRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import service.ProteinFeaturesService;
import service.StorageService;
import exceptions.IncorrectRequestException;
import springconfiguration.AppProperties;

@Service
public class ProteinFeaturesServiceImpl extends BioUniverseServiceImpl implements ProteinFeaturesService {
	private final int defaultLastJobId = 1;
	private final String bootstrapFilePostfix = "_consensus";
	private Map<Integer, String> counterToStageOneInput = new HashMap<>();
	private Map<Integer, String> counterToStageTwoInputs = new HashMap<>();
	private Map<Integer, String> counterToStagePartialOneInput = new HashMap<>();
	private Map<Integer, String> counterToStagePartialTwoInputs = new HashMap<>();
    private Map<Integer, String> counterToStageOneInputWithRedund = new HashMap<>();
	private Map<Integer, String> counterToStageTwoInputsWithRedund = new HashMap<>();


	public ProteinFeaturesServiceImpl(final StorageService storageService, final AppProperties properties, final BioJobDao bioJobDao, final BioJobResultDao bioJobResultDao) {
		super(storageService, properties, bioJobResultDao, bioJobDao);
        counterToStageOneInput.put(0, "['Processing input.']");
        counterToStageOneInput.put(1, "['Processing input.', 'Predicting proteins features.']");
        counterToStageOneInput.put(2, "['Processing input.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.']");
        counterToStageOneInput.put(3, "['Processing input.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.', 'Ordering alignment and putting features and tree together.-last']");

        counterToStageTwoInputs.put(1, "['Processing input.', 'Predicting proteins features.']");
        counterToStageTwoInputs.put(2, "['Processing input.', 'Predicting proteins features.']");
        counterToStageTwoInputs.put(3, "['Processing input.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.']");
        counterToStageTwoInputs.put(4, "['Processing input.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.', 'Ordering alignment and putting features and tree together.-last']");

        counterToStageOneInputWithRedund.put(0, "['Processing input.']");
        counterToStageOneInputWithRedund.put(1, "['Processing input.', 'Reducing sequence redundancy.']");
        counterToStageOneInputWithRedund.put(2, "['Processing input.', 'Reducing sequence redundancy.', 'Predicting proteins features.']");
        counterToStageOneInputWithRedund.put(3, "['Processing input.', 'Reducing sequence redundancy.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.']");
        counterToStageOneInputWithRedund.put(4, "['Processing input.', 'Reducing sequence redundancy.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.', 'Ordering alignment and putting features and tree together.-last']");

        counterToStageTwoInputsWithRedund.put(1, "['Processing input.']");
        counterToStageTwoInputsWithRedund.put(2, "['Processing input.', 'Reducing sequence redundancy.']");
        counterToStageTwoInputsWithRedund.put(3, "['Processing input.', 'Reducing sequence redundancy.', 'Predicting proteins features.']");
        counterToStageTwoInputsWithRedund.put(4, "['Processing input.', 'Reducing sequence redundancy.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.']");
        counterToStageTwoInputsWithRedund.put(5, "['Processing input.', 'Reducing sequence redundancy.', 'Predicting proteins features.', 'Aligning sequences and building phylogenetic tree.', 'Ordering alignment and putting features and tree together.-last']");

        counterToStagePartialOneInput.put(0, "['Processing input.']");
        counterToStagePartialOneInput.put(1, "['Processing input.', 'Predicting proteins features.']");
        counterToStagePartialOneInput.put(2, "['Processing input.', 'Predicting proteins features.', 'Ordering alignment and putting features and tree together.-last']");

        counterToStagePartialTwoInputs.put(1, "['Processing input.']");
        counterToStagePartialTwoInputs.put(2, "['Processing input.', 'Predicting proteins features.']");
        counterToStagePartialTwoInputs.put(3, "['Processing input.', 'Predicting proteins features.', 'Ordering alignment and putting features and tree together.-last']");
	}

	private String getDomainPredictionDb(String dbName) {
	    String db = dbName.split(" ")[0] + " ";
        switch (dbName.split(" ")[1]) {
            case "cdd":
                db = db + super.getProperties().getRpsblastCddSuper();
                break;
            case "cdd_ncbi":
                db = db + super.getProperties().getRpsblastCddNcbi();
                break;
            case "pfam":
                db = db + super.getProperties().getRpsblastPfam();
                break;
            case "cog":
                db = db + super.getProperties().getRpsblastCog();
                break;
            case "kog":
                db = db + super.getProperties().getRpsblastKog();
                break;
            case "smart":
                db = db + super.getProperties().getRpsblastSmart();
                break;
            case "prk":
                db = db + super.getProperties().getRpsblastPrk();
                break;
            case "tigr":
                db = db + super.getProperties().getRpsblastTigr();
                break;
            case "pfam-only":
                db = db + super.getProperties().getPfam();
                break;
            case "pfam_and_mist":
                db = db + super.getProperties().getPfamAndMist();
                break;
        }
        return db;
    }

    @Override
    public ProtoTreeInternal storeFilesAndPrepareCommandArguments(ProtoTreeRequest protoTreeRequest) throws IncorrectRequestException {
	    if (protoTreeRequest.isFullPipeline().equals("true"))
            return fullPipelineProcessing(protoTreeRequest);
        else
            return partialPipelineProcessing(protoTreeRequest);
    }

    private ProtoTreeInternal fullPipelineProcessing(ProtoTreeRequest protoTreeRequest) throws IncorrectRequestException {
        ProtoTreeInternal protoTreeInternal = super.storeFileAndGetInternalRepresentation(protoTreeRequest);
        String redundancy = protoTreeInternal.getRedundancy() != null && protoTreeInternal.getSecondFileName() == null ? protoTreeInternal.getRedundancy() : null;
        List<String> listOfPrograms = new LinkedList<>();
        List<List<String>> listOfArgumentLists = new LinkedList<>();

        List<String> argsForPrepareNames = new LinkedList<>();
        List<String> argsForPrepareNamesSecond = new LinkedList<>();
        List<String> argsForProteinFeatures = new LinkedList<>();
        List<String> argsForAlignmentAndTree = new LinkedList<>();
        List<String> argsForTreeWithDomains = new LinkedList<>();
        List<String> argsForCdHit = new LinkedList<>();

        initFullPipeArgsForPrepareNames(protoTreeInternal, argsForPrepareNames, argsForPrepareNamesSecond, listOfPrograms, listOfArgumentLists);

        String cdHitOutputFile = super.getRandomFileName(null);
        if (redundancy != null && protoTreeInternal.getSecondFileName() == null) {
            argsForCdHit.addAll(Arrays.asList(
                    protoTreeInternal.getFirstFileName(),
                    ParamPrefixes.OUTPUT.getPrefix() + cdHitOutputFile,
                    redundancy,
                    ParamPrefixes.CDHIT_PATH.getPrefix() + super.getProperties().getCdhit(),
                    ParamPrefixes.MEMORY.getPrefix() + super.getProperties().getCdhitMemory(),
                    ParamPrefixes.THREADS_GENERAL.getPrefix() + super.getProperties().getCdhitThreadNum()
            ));
            protoTreeInternal.setFirstFileName(ParamPrefixes.INPUT.getPrefix() + cdHitOutputFile);
        }
        protoTreeInternal.setFields();

        String hmmscanOrRpsbOutFile = super.getRandomFileName(null);
        String rpsbProcOutFile = super.getRandomFileName(null);

        String tmhmmscanOutFile = super.getRandomFileName(null);
        String proteinFeaturesOutFile = super.getRandomFileName(null);
        String segmakserOutFile = super.getRandomFileName(null);


        argsForProteinFeatures.addAll(protoTreeInternal.getFieldsForFeaturesPrediction());
        argsForProteinFeatures.addAll(Arrays.asList(
                protoTreeInternal.getFirstFileName(),
                getDomainPredictionDb(protoTreeInternal.getDomainPredictionDb()),
                ParamPrefixes.OUTPUT_FOURTH.getPrefix() + hmmscanOrRpsbOutFile,
                ParamPrefixes.OUTPUT_FIFTH.getPrefix() + rpsbProcOutFile,
                ParamPrefixes.OUTPUT_SIXTH.getPrefix() + tmhmmscanOutFile,
                ParamPrefixes.OUTPUT_SEVENTH.getPrefix() + segmakserOutFile,
                ParamPrefixes.HMMSCAN_DB_PATH.getPrefix() + super.getProperties().getHmmscanDbPath(),
                ParamPrefixes.RPSBLAST_DB_PATH.getPrefix() + super.getProperties().getRpsblastDbPath(),
                ParamPrefixes.RPSBPROC_DB_PATH.getPrefix() + super.getProperties().getRpsprocDbPath(),
                ParamPrefixes.HMMSCAN_PATH.getPrefix() + super.getProperties().getHmmscanPath(),
                ParamPrefixes.RPSBLAST_PATH.getPrefix() + super.getProperties().getRpsblastPath(),
                ParamPrefixes.RPSBPROC_PATH.getPrefix() + super.getProperties().getRpsbprocPath(),
                ParamPrefixes.TMHMM_PATH.getPrefix() + super.getProperties().getTmhmm2Path(),
                ParamPrefixes.SEGMASKER_PATH.getPrefix() + super.getProperties().getSegmaskerPath(),
                ParamPrefixes.THREADS_GENERAL.getPrefix() + super.getProperties().getHmmscanThreadNum(),
                ParamPrefixes.OUTPUT_THIRD.getPrefix() + proteinFeaturesOutFile
        ));

        String outAlgnFile = super.getRandomFileName(".fa");
        String outNewickTree = super.getRandomFileName("noPostfix");
        argsForAlignmentAndTree.addAll(protoTreeInternal.getFieldsForAlignmentAndTreeBuild());
        argsForAlignmentAndTree.addAll(Arrays.asList(
                ParamPrefixes.MAFFT_PATH.getPrefix() + super.getProperties().getMafft(),
                ParamPrefixes.MEGACC_PATH.getPrefix() + super.getProperties().getMegacc(),
                ParamPrefixes.OUTPUT_PARAMS.getPrefix() + super.getRandomFileName(null),
                ParamPrefixes.OUTPUT_TREE.getPrefix() + outNewickTree,
                ParamPrefixes.THREADS_MAFFT.getPrefix() + super.getProperties().getMafftThreadNum(),
                ParamPrefixes.THREADS_GENERAL.getPrefix() + super.getProperties().getMegaThreadNum(),
                ParamPrefixes.OUTPUT.getPrefix() + outAlgnFile
        ));

        if (!protoTreeRequest.getPhylogenyTest().equals("none")) {
            outNewickTree = outNewickTree + bootstrapFilePostfix;
        }

        String outNewickFile = super.getRandomFileName(".newick");
        String outSvgFile = super.getRandomFileName(".svg");
        String outOrderedAlgnFile = super.getRandomFileName(".fa");

        String proteinFeaturesChangedOutFile = super.getRandomFileName(null);
        argsForTreeWithDomains.addAll(protoTreeInternal.getFieldsForTreeAndDomains());
        argsForTreeWithDomains.addAll(Arrays.asList(
                protoTreeInternal.getFirstFileName(),
                ParamPrefixes.INPUT_SECOND.getPrefix() + outAlgnFile,
                ParamPrefixes.INPUT_THIRD.getPrefix() + outNewickTree + ".nwk",
                ParamPrefixes.INPUT_FOURTH.getPrefix() + proteinFeaturesOutFile,
                ParamPrefixes.OUTPUT.getPrefix() + outOrderedAlgnFile,
                ParamPrefixes.OUTPUT_SECOND.getPrefix() + outSvgFile,
                ParamPrefixes.OUTPUT_THIRD.getPrefix() + outNewickFile,
                ParamPrefixes.OUTPUT_FOURTH.getPrefix() + proteinFeaturesChangedOutFile
        ));

        if (redundancy == null)
            protoTreeInternal.setOutputFilesNames(Arrays.asList(outNewickFile, outSvgFile, outOrderedAlgnFile, proteinFeaturesChangedOutFile));
        else
            protoTreeInternal.setOutputFilesNames(Arrays.asList(outNewickFile, outSvgFile, outOrderedAlgnFile, proteinFeaturesChangedOutFile, cdHitOutputFile+".clstr"));

        listOfPrograms.addAll(Arrays.asList(
                super.getProperties().getCalculateProteinFeatures(),
                super.getProperties().getAlignAndBuildTree(),
                super.getProperties().getProtoTreeProgram()
        ));
        listOfArgumentLists.addAll(Arrays.asList(
                argsForProteinFeatures,
                argsForAlignmentAndTree,
                argsForTreeWithDomains
        ));
        if (redundancy != null) {
            listOfPrograms.add(1, super.getProperties().getReduceWithCdHit());
            listOfArgumentLists.add(1, argsForCdHit);
        }
        String[] arrayOfInterpreters = super.prepareInterpreters(listOfPrograms.size());
        String[] arrayOfPrograms = listOfPrograms.toArray(new String[listOfPrograms.size()]);

        super.prepareCommandArgumentsCommon(protoTreeInternal, arrayOfInterpreters, arrayOfPrograms, listOfArgumentLists);

        return protoTreeInternal;
    }

    private ProtoTreeInternal partialPipelineProcessing(ProtoTreeRequest protoTreeRequest) throws IncorrectRequestException {
        ProtoTreeInternal protoTreeInternal = super.storeFileAndGetInternalRepresentation(protoTreeRequest);
        List<String> listOfPrograms = new LinkedList<>();
        List<List<String>> listOfArgumentLists = new LinkedList<>();

        List<String> argsForPrepareNames = new LinkedList<>();
        List<String> argsForPrepareNamesSecond = new LinkedList<>();
        List<String> argsForProteinFeatures = new LinkedList<>();
        List<String> argsForTreeWithDomains = new LinkedList<>();

        protoTreeInternal.setFieldsForPrepareNames();
        String sequencePreparedFile = super.getRandomFileName(null);
        String treePreparedFile = super.getRandomFileName(null);
        argsForPrepareNames.addAll(protoTreeInternal.getFieldsForPrepareNames());
        argsForPrepareNames.add(ParamPrefixes.REMOVE_DASHES.getPrefix() + "true");
        argsForPrepareNames.add(ParamPrefixes.FETCH_FROM_MIST.getPrefix() + super.getProperties().getFetchFromMist());
        argsForPrepareNames.add(ParamPrefixes.FETCH_FROM_NCBI.getPrefix() + super.getProperties().getFetchFromNCBI());
        argsForPrepareNames.add(ParamPrefixes.PROCESS_NUMBER.getPrefix() + super.getProperties().getFetchFromMistProcNum());
        if (protoTreeInternal.getFirstFileName() != null) {
            argsForPrepareNames.add(protoTreeInternal.getFirstFileName());
        }
        argsForPrepareNames.addAll(Arrays.asList(
                ParamPrefixes.OUTPUT.getPrefix() + sequencePreparedFile,
                protoTreeInternal.getTreeFile(),
                ParamPrefixes.OUTPUT_SECOND.getPrefix() + treePreparedFile));
        listOfPrograms.add(super.getProperties().getPrepareNames());
        listOfArgumentLists.add(argsForPrepareNames);

        protoTreeInternal.setFirstFileName(ParamPrefixes.INPUT.getPrefix() + sequencePreparedFile);
        protoTreeInternal.setTreeFile(ParamPrefixes.INPUT_THIRD.getPrefix() + treePreparedFile);

        if (protoTreeInternal.getAlignmentFile() != null) {
            String alignmentPreparedFile = super.getRandomFileName(null);
            argsForPrepareNamesSecond.addAll(Arrays.asList(protoTreeInternal.getAlignmentFile(), ParamPrefixes.OUTPUT.getPrefix() + alignmentPreparedFile));
            argsForPrepareNamesSecond.add(ParamPrefixes.REMOVE_DASHES.getPrefix() + "false");
            protoTreeInternal.setAlignmentFile(ParamPrefixes.INPUT_SECOND.getPrefix() + alignmentPreparedFile);
            listOfPrograms.add(super.getProperties().getPrepareNames());
            listOfArgumentLists.add(argsForPrepareNamesSecond);
        }

        protoTreeInternal.setFields();

        String hmmscanOrRpsbOutFile = super.getRandomFileName(null);
        String rpsbProcOutFile = super.getRandomFileName(null);

        String tmhmmscanOutFile = super.getRandomFileName(null);
        String proteinFeaturesOutFile = super.getRandomFileName(null);
        String segmakserOutFile = super.getRandomFileName(null);

        argsForProteinFeatures.addAll(protoTreeInternal.getFieldsForFeaturesPrediction());
        argsForProteinFeatures.addAll(Arrays.asList(
                protoTreeInternal.getFirstFileName(),
                getDomainPredictionDb(protoTreeInternal.getDomainPredictionDb()),
                ParamPrefixes.OUTPUT_FOURTH.getPrefix() + hmmscanOrRpsbOutFile,
                ParamPrefixes.OUTPUT_FIFTH.getPrefix() + rpsbProcOutFile,
                ParamPrefixes.OUTPUT_SIXTH.getPrefix() + tmhmmscanOutFile,
                ParamPrefixes.OUTPUT_SEVENTH.getPrefix() + segmakserOutFile,
                ParamPrefixes.HMMSCAN_DB_PATH.getPrefix() + super.getProperties().getHmmscanDbPath(),
                ParamPrefixes.RPSBLAST_DB_PATH.getPrefix() + super.getProperties().getRpsblastDbPath(),
                ParamPrefixes.RPSBPROC_DB_PATH.getPrefix() + super.getProperties().getRpsprocDbPath(),
                ParamPrefixes.HMMSCAN_PATH.getPrefix() + super.getProperties().getHmmscanPath(),
                ParamPrefixes.RPSBLAST_PATH.getPrefix() + super.getProperties().getRpsblastPath(),
                ParamPrefixes.RPSBPROC_PATH.getPrefix() + super.getProperties().getRpsbprocPath(),
                ParamPrefixes.TMHMM_PATH.getPrefix() + super.getProperties().getTmhmm2Path(),
                ParamPrefixes.SEGMASKER_PATH.getPrefix() + super.getProperties().getSegmaskerPath(),
                ParamPrefixes.THREADS_GENERAL.getPrefix() + super.getProperties().getHmmscanThreadNum(),
                ParamPrefixes.OUTPUT_THIRD.getPrefix() + proteinFeaturesOutFile
        ));

        String outNewickFile = super.getRandomFileName(".newick");
        String outSvgFile = super.getRandomFileName(".svg");
        String proteinFeaturesChangedOutFile = super.getRandomFileName(null);
        argsForTreeWithDomains.addAll(protoTreeInternal.getFieldsForTreeAndDomains());
        argsForTreeWithDomains.addAll(Arrays.asList(
                protoTreeInternal.getFirstFileName(),
                protoTreeInternal.getTreeFile(),
                ParamPrefixes.INPUT_FOURTH.getPrefix() + proteinFeaturesOutFile,
                ParamPrefixes.OUTPUT_SECOND.getPrefix() + outSvgFile,
                ParamPrefixes.OUTPUT_THIRD.getPrefix() + outNewickFile,
                ParamPrefixes.OUTPUT_FOURTH.getPrefix() + proteinFeaturesChangedOutFile
        ));

        if (protoTreeInternal.getAlignmentFile() != null) {
            String outOrderedAlgnFile = super.getRandomFileName(".fa");
            argsForTreeWithDomains.add(protoTreeInternal.getAlignmentFile());
            argsForTreeWithDomains.add(ParamPrefixes.OUTPUT.getPrefix() + outOrderedAlgnFile);
            protoTreeInternal.setOutputFilesNames(Arrays.asList(outNewickFile, outSvgFile, outOrderedAlgnFile, proteinFeaturesChangedOutFile));
        } else {
            protoTreeInternal.setOutputFilesNames(Arrays.asList(outNewickFile, outSvgFile, proteinFeaturesChangedOutFile));
        }


        listOfPrograms.addAll(Arrays.asList(
                super.getProperties().getCalculateProteinFeatures(),
                super.getProperties().getProtoTreeProgram()
        ));

        String[] arrayOfInterpreters = super.prepareInterpreters(listOfPrograms.size());
        String[] arrayOfPrograms = listOfPrograms.toArray(new String[listOfPrograms.size()]);

        listOfArgumentLists.addAll(Arrays.asList(
                argsForProteinFeatures,
                argsForTreeWithDomains
        ));
        super.prepareCommandArgumentsCommon(protoTreeInternal, arrayOfInterpreters, arrayOfPrograms, listOfArgumentLists);
        return protoTreeInternal;
    }

    private String initFullPipeArgsForPrepareNames(ProtoTreeInternal protoTreeInternal, List<String> argsForPrepareNames, List<String> argsForPrepareNamesSecond,
                                                   List<String> listOfPrograms, List<List<String>> listOfArgumentLists) {
        protoTreeInternal.setFieldsForPrepareNames();
        String firstPreparedFile = super.getRandomFileName(null);
        argsForPrepareNames.addAll(Arrays.asList(protoTreeInternal.getFirstFileName(), ParamPrefixes.OUTPUT.getPrefix() + firstPreparedFile));
        argsForPrepareNames.add(ParamPrefixes.REMOVE_DASHES.getPrefix() + "true");
        protoTreeInternal.setFirstFileName(ParamPrefixes.INPUT.getPrefix() + firstPreparedFile);
        String inputFileNameForProtFeatures = protoTreeInternal.getFirstFileName();
        listOfPrograms.add(super.getProperties().getPrepareNames());

        if (protoTreeInternal.getSecondFileName() != null) {
            // We don't fetch sequences if a second file with fragments of sequences is provided
            String secondPreparedFile = super.getRandomFileName(null);
            argsForPrepareNamesSecond.addAll(Arrays.asList(protoTreeInternal.getSecondFileName(), ParamPrefixes.OUTPUT.getPrefix() + secondPreparedFile));
            argsForPrepareNamesSecond.add(ParamPrefixes.REMOVE_DASHES.getPrefix() + "true");
            protoTreeInternal.setSecondFileName(ParamPrefixes.INPUT.getPrefix() + secondPreparedFile);
            inputFileNameForProtFeatures = protoTreeInternal.getSecondFileName();
            listOfPrograms.add(super.getProperties().getPrepareNames());
            listOfArgumentLists.add(argsForPrepareNamesSecond);
        } else {
            // If Second file is null we need to add to argsForPrepareNames additional fields, FETCH_FROM_MIST and FETCH_FROM_NCBI, PROCESS_NUMBER
            argsForPrepareNames.addAll(protoTreeInternal.getFieldsForPrepareNames());
            argsForPrepareNames.add(ParamPrefixes.FETCH_FROM_MIST.getPrefix() + super.getProperties().getFetchFromMist());
            argsForPrepareNames.add(ParamPrefixes.FETCH_FROM_NCBI.getPrefix() + super.getProperties().getFetchFromNCBI());
            argsForPrepareNames.add(ParamPrefixes.PROCESS_NUMBER.getPrefix() + super.getProperties().getFetchFromMistProcNum());
        }
        // We adding argsForPrepareNames to listOfArgumentLists
        listOfArgumentLists.add(argsForPrepareNames);
        return inputFileNameForProtFeatures;
    }

    @Override
    @Async
    public void runMainProgram(ProtoTreeInternal protoTreeInternal) throws IncorrectRequestException {
	    int counter = 0;
        for (List<String> commandArgument : protoTreeInternal.getCommandsAndArguments()) {
            if (protoTreeInternal.isFullPipeline().equals("true")) {
                if (protoTreeInternal.getSecondFileName() == null) {
                    if(protoTreeInternal.getRedundancy() == null)
                        super.saveStage(protoTreeInternal, counter, counterToStageOneInput);
                    else
                        super.saveStage(protoTreeInternal, counter, counterToStageOneInputWithRedund);
                } else
                    super.saveStage(protoTreeInternal, counter, counterToStageTwoInputs);
            } else if (protoTreeInternal.isFullPipeline().equals("false")) {
                if (protoTreeInternal.getAlignmentFile() == null)
                    super.saveStage(protoTreeInternal, counter, counterToStagePartialOneInput);
                else
                    super.saveStage(protoTreeInternal, counter, counterToStagePartialTwoInputs);
            }
            counter++;
            try {
                super.launchProcess(commandArgument, protoTreeInternal);
            } catch (Exception exception) {
                if (exception.getMessage().contains(Status.megaError.getStatusEnum()))
                    super.saveError(protoTreeInternal, exception.getMessage());
                else
                    super.saveError(protoTreeInternal, null);
                throw exception;
            }
        }
        super.saveResultToDb(protoTreeInternal);
    }


}
