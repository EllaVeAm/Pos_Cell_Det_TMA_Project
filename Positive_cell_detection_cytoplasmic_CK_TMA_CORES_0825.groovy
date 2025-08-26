// QuPath >= 0.4.x to run on TMA cores
import qupath.lib.objects.classes.PathClass
import static qupath.lib.gui.scripting.QPEx.*

// Defiition General Settings (see below)
double REQ_PX       = 0.19
String POS_FEATURE   = 'Cytoplasm: DAB OD mean'   
double POS_THRESHOLD = 1.0
double BKG_DAB   = 10.0
double SIGMA_DAB = 0.8
double THRESH_DAB= 0.01
double EXPAND_DAB= 2.5

if (getCurrentImageData() == null) { print 'No image open.'; return }

setImageType('BRIGHTFIELD_H_DAB')

// Channel names based on color deconvolution
def ch = getCurrentImageData().getServer().getMetadata().getChannels()*.getName()
def H_IMG  = ['Hematoxylin OD','Hematoxylin'].find { ch.contains(it) }     
def DAB_IMG= ['DAB OD','DAB'].find { ch.contains(it) }                     
println "Using channels -> H: '${H_IMG ?: "(plugin default)"}', DAB: '${DAB_IMG ?: "(plugin default)"}'"

// Gather TMA cores
def cores = getTMACoreList().findAll { it != null && !it.isMissing() }
if (cores.isEmpty()) { print 'No usable TMA cores (all missing?).'; return }

cores.each { core ->
    selectObjects([core])

    // Clear onöy detections inside this core (do not wipe other cores)
    def coreGeom = core.getROI()?.getGeometry()
    def detsInCore = new ArrayList(getDetectionObjects()).findAll { d ->
        def g = d.getROI()?.getGeometry()
        g != null && coreGeom != null && g.intersects(coreGeom)
    }
    if (!detsInCore.isEmpty())
        removeObjects(detsInCore, true)

    // PASS A: Hematoxylin (broad recall) 
    def pA = [
      'requestedPixelSizeMicrons': REQ_PX,
      'backgroundRadiusMicrons'  : 50.0,
      'medianRadiusMicrons'      : 1.2,
      'sigmaMicrons'             : 1.5,
      'minAreaMicrons'           : 15.0,
      'maxAreaMicrons'           : 1200.0,
      'threshold'                : 0.03,   
      'maxBackground'            : 1.0,
      'watershedPostProcess'     : true,
      'cellExpansionMicrons'     : 5.0,
      'includeNuclei'            : true,
      'smoothBoundaries'         : true,
      'makeMeasurements'         : true
    ]
    if (H_IMG) pA['detectionImage'] = H_IMG
    runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', pA)
    def TMP_OD = PathClass.fromString('TMP_OD')
    def passA  = new ArrayList(getCellObjects()); passA.each { it.setPathClass(TMP_OD) }

    // PASS B: DAB-only (aggressive split)
    def pB = [
      'requestedPixelSizeMicrons': Math.min(REQ_PX, 0.19),
      'backgroundRadiusMicrons'  : BKG_DAB,
      'medianRadiusMicrons'      : 1.0,
      'sigmaMicrons'             : SIGMA_DAB,
      'minAreaMicrons'           : 6.0,
      'maxAreaMicrons'           : 350.0,
      'threshold'                : THRESH_DAB,
      'maxBackground'            : 1.0,
      'watershedPostProcess'     : true,
      'cellExpansionMicrons'     : EXPAND_DAB,
      'includeNuclei'            : true,
      'smoothBoundaries'         : true,
      'makeMeasurements'         : true
    ]
    if (DAB_IMG) pB['detectionImage'] = DAB_IMG
    runPlugin('qupath.imagej.detect.cells.WatershedCellDetection', pB)
    def TMP_DAB = PathClass.fromString('TMP_DAB')
    def passB   = new ArrayList(getCellObjects().findAll { it.getPathClass() == null })
    passB.each { it.setPathClass(TMP_DAB) }

    // Merge by overlap: replace OD with DAB-split cells
    def toRemoveA = []
    passA.each { a ->
      def ag = a.getROI()?.getGeometry(); if (ag == null) return
      if (passB.any { b -> def bg = b.getROI()?.getGeometry(); bg != null && ag.intersects(bg) })
        toRemoveA << a
    }
    getCurrentHierarchy().removeObjects(toRemoveA, true)

    // Classification: positive/negative
    def CLS_POS = PathClass.fromString('Positive')
    def CLS_NEG = PathClass.fromString('Negative')
    new ArrayList(getCellObjects()).each { c ->
      def ml = c.getMeasurementList()
      def val = ml != null ? ml[POS_FEATURE] : null
      c.setPathClass((val != null && val >= POS_THRESHOLD) ? CLS_POS : CLS_NEG)
    }
    fireHierarchyUpdate()

    // Per-core log 
    def cells = new ArrayList(getCellObjects()).findAll { obj ->
        def g = obj.getROI()?.getGeometry()
        g != null && coreGeom != null && g.intersects(coreGeom)
    }
    def countNamed = { n -> cells.count { it.getPathClass()?.getName() == n } }
    println "${core.getName()}  Positive: ${countNamed('Positive')}  " +
            "Negative: ${countNamed('Negative')}  Total: ${cells.size()}"
}
