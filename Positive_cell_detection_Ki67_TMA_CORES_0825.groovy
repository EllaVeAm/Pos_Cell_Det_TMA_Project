// QuPath >= 0.4.x to run on TMA cores
import qupath.lib.objects.classes.PathClass
import static qupath.lib.gui.scripting.QPEx.*

// sanity check
if (getCurrentImageData() == null) { print 'No image open.'; return }

// global setup
setImageType('BRIGHTFIELD_H_DAB')
setColorDeconvolutionStains('{"Name" : "Ki67", "Stain 1" : "Hematoxylin", "Values 1" : "0.8256099748801383 0.5279769334125614 0.1990189115704461", "Stain 2" : "DAB", "Values 2" : "0.21033287066573078 0.4582826870238786 0.8635606882505055", "Background" : " 252 251 249"}')

// DetectionImage Settings
def ch = getCurrentImageData().getServer().getMetadata().getChannels()*.getName()
def DET_IMG = ['Optical density sum','Hematoxylin OD','Hematoxylin','DAB OD','DAB'].find { ch.contains(it) } ?: 'Optical density sum'
println "PositiveCellDetection will use detectionImageBrightfield = '${DET_IMG}'"

// Parameters: your PositiveCellDetection settings
def params = [
  'detectionImageBrightfield': DET_IMG,
  'requestedPixelSizeMicrons': 0.1945,
  'backgroundRadiusMicrons'  : 40.0,
  'backgroundByReconstruction': true,
  'medianRadiusMicrons'      : 1.2,
  'sigmaMicrons'             : 1.5,
  'minAreaMicrons'           : 9.5,
  'maxAreaMicrons'           : 400.0,
  'threshold'                : 0.05,
  'maxBackground'            : 2.0,
  'watershedPostProcess'     : true,
  'excludeDAB'               : false,
  'cellExpansionMicrons'     : 0.1,
  'includeNuclei'            : true,
  'smoothBoundaries'         : true,
  'makeMeasurements'         : true,
  // nuclear positivity using single threshold
  'thresholdCompartment'     : 'Nucleus: DAB OD mean',
  'thresholdPositive1'       : 0.15,
  'thresholdPositive2'       : 0.4,
  'thresholdPositive3'       : 0.6000000000000001,
  'singleThreshold'          : true
]

// get TMA cores
def cores = getTMACoreList().findAll { it != null && !it.isMissing() }
if (cores.isEmpty()) { print 'No usable TMA cores (all missing?).'; return }
println "Processing ${cores.size()} TMA core(s)..."

// loop cores 
cores.each { core ->
  // limit work to this core
  selectObjects([core])

  // clear ONLY detections inside this core (don’t wipe the whole slide)
  def coreGeom = core.getROI()?.getGeometry()
  def detsInCore = new ArrayList(getDetectionObjects()).findAll { d ->
    def g = d.getROI()?.getGeometry()
    g != null && coreGeom != null && g.intersects(coreGeom)
  }
  if (!detsInCore.isEmpty())
    removeObjects(detsInCore, true)

  // run your PositiveCellDetection
  runPlugin('qupath.imagej.detect.cells.PositiveCellDetection', params)

  // Explicit classification to ensure each cell has a PathClass
  def CLS_POS = PathClass.fromString('Positive')
  def CLS_NEG = PathClass.fromString('Negative')
  double CUTOFF = 0.15   // keep in sync with thresholdPositive1

  new ArrayList(getCellObjects()).each { c ->
    def g = c.getROI()?.getGeometry()
    if (g != null && coreGeom != null && g.intersects(coreGeom)) {
      def v = c.getMeasurementList()['Nucleus: DAB OD mean']
      c.setPathClass((v != null && v >= CUTOFF) ? CLS_POS : CLS_NEG)
    }
  }
  fireHierarchyUpdate()

  // quick per-core summary (now includes Positive/Negative)
  def cellsHere = new ArrayList(getCellObjects()).findAll { c ->
    def g = c.getROI()?.getGeometry()
    g != null && coreGeom != null && g.intersects(coreGeom)
  }
  def posCount = cellsHere.count { it.getPathClass()?.getName() == 'Positive' }
  def negCount = cellsHere.count { it.getPathClass()?.getName() == 'Negative' }
  println "${core.getName()}  Cells: ${cellsHere.size()}  Positive: ${posCount}  Negative: ${negCount}"
}


