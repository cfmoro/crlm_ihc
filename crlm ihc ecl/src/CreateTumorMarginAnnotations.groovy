/**
 * Script to help with annotating tumor regions, separating the tumor margin from the center.
 *
 * Here, each of the margin regions is approximately 500 microns in width.
 *
 * NOTE: This version has been updated for v0.2.0-m6 and to use Java Topology Suite.
 *
 * @author Pete Bankhead
 * @author Modified by Carlos F Moro and Sara Harrizi
 */
import org.locationtech.jts.geom.Geometry
import qupath.lib.common.GeneralTools
import qupath.lib.gui.scripting.QPEx
import qupath.lib.objects.PathObject
import qupath.lib.objects.PathObjects
import qupath.lib.roi.GeometryTools
import qupath.lib.roi.ROIs

import static qupath.lib.gui.scripting.QPEx.*

//-----
// Some things you might want to change

// Define the colors
def colorInnerMargin = getColorRGB(0, 0, 200)
def colorOuterMargin = getColorRGB(0, 200, 0)
def colorCentral = getColorRGB(0, 0, 0)

// Choose whether to lock the annotations or not (it's generally a good idea to avoid accidentally moving them)
def lockAnnotations = true

// CRLM enhancements
// How much to expand each region
def widthsOuter = [800] //
println('widthsOuter: ' + widthsOuter)
def widthsInner = [] //
println('widthsInner: ' + widthsInner)

def removeOrigTumorAnnot = false

//-----

// Extract the main info we need
def imageData = getCurrentImageData()
def hierarchy = imageData.getHierarchy()
def server = imageData.getServer()

// We need the pixel size
def cal = server.getPixelCalibration()
if (!cal.hasPixelSizeMicrons()) {
  print 'We need the pixel size information here!'
  return
}
if (!GeneralTools.almostTheSame(cal.getPixelWidthMicrons(), cal.getPixelHeightMicrons(), 0.0001)) {
  print 'Warning! The pixel width & height are different; the average of both will be used'
}

// Get annotation & detections
def annotations = getAnnotationObjects()
def invFrontAnnotations = annotations.findAll{ it.getPathClass() == getPathClass('Invasion front') }
if (invFrontAnnotations.size() != 1) {
	println 'Ups, one and only one invasion front annnotatio required and supported for the moment'
	return
}
def selected = invFrontAnnotations[0]

// Extract the ROI & plane
def roiOriginal = selected.getROI()
def plane = roiOriginal.getImagePlane()

// If we have at most one other annotation, it represents the tissue
def Geometry areaTissue
PathObject tissueAnnotation
if (annotations.isEmpty()) {
  areaTissue = ROIs.createRectangleROI(0, 0, server.getWidth(), server.getHeight(), plane).getGeometry()
} else if (annotations.size() == 1) {
  tissueAnnotation = annotations.get(0)
  areaTissue = tissueAnnotation.getROI().getGeometry()
} else {
   // Obtain the tissue annotation, must be of class Region*
   areaTissue = annotations.findAll{ it.getPathClass() == getPathClass("Region*")}.get(0).getROI().getGeometry()
   print(areaTissue)
   
}

// Calculate how much to expand
def expandPixelsOuter = widthsOuter*.div(cal.getAveragedPixelSizeMicrons()) // same as collect
println('expandPixelsOuter: ' + expandPixelsOuter)
def expandPixelsInner = widthsInner*.div(cal.getAveragedPixelSizeMicrons()) // same as collect
println('expandPixelsInner: ' + expandPixelsInner)

// Get the tumor area
def areaTumor = roiOriginal.getGeometry()

// Initialize arrays of border margins
def annotOuters = []
def annotInners = []

// Create outer margins/bands
def sumPrevOuters = 0
expandPixelsOuter.eachWithIndex { width, index ->
 
   def widthOut = sumPrevOuters + expandPixelsOuter[index]
   
   println "Create outer ${index+1} of width ${widthsOuter[index]} with widthOut ${widthOut} and sumPrevOuters ${sumPrevOuters}"
   
   def geomOuter = areaTumor.buffer(widthOut)
   geomOuter = geomOuter.difference(areaTumor.buffer(sumPrevOuters))
   geomOuter = geomOuter.intersection(areaTissue)
   
   def roiOuter = GeometryTools.geometryToROI(geomOuter, plane)
   
   def annotationOuter = PathObjects.createAnnotationObject(roiOuter)
	   annotationOuter.setPathClass(getPathClass('Band*'))
	   annotationOuter.setName("Outer margin " + (index+1))
	   annotationOuter.setColorRGB(colorOuterMargin)
	   annotationOuter.getMeasurementList().putMeasurement("band index", index+1)
	   annotationOuter.getMeasurementList().putMeasurement("band width um", widthsOuter[index])
	   annotationOuter.getMeasurementList().putMeasurement("band width px", width)
	   annotOuters.add(annotationOuter)
			 
	sumPrevOuters += widthOut
 }
 
 println()
 
 // Create inner margins/bands
def sumPrevInners = 0
expandPixelsInner.eachWithIndex { width, index ->
   
   def widthIn = sumPrevInners + expandPixelsInner[index]
   println "Create inner -${index+1} of width ${widthsInner[index]} with sumPrevInners ${sumPrevInners} and widthIn ${widthIn}"
   
   def geomInner = areaTumor.buffer(-sumPrevInners)
   geomInner = geomInner.difference(areaTumor.buffer(-widthIn))
   geomInner = geomInner.intersection(areaTissue)
   
   def roiInner = GeometryTools.geometryToROI(geomInner, plane)
   
   def annotationInner = PathObjects.createAnnotationObject(roiInner)
	   annotationInner.setPathClass(getPathClass('Band*'))
	   annotationInner.setName("Inner margin " + (index+1))
	   annotationInner.setColorRGB(colorInnerMargin)
	   annotationInner.getMeasurementList().putMeasurement("band index", -(index+1))
	   annotationInner.getMeasurementList().putMeasurement("band width um", widthsInner[index])
	   annotationInner.getMeasurementList().putMeasurement("band width px", width)
	   annotInners.add(annotationInner)
   
   sumPrevInners += expandPixelsInner[index]
 }
	 
def annotationsToAdd =  annotOuters //
annotationsToAdd.addAll(annotInners)
annotationsToAdd.each {it.setLocked(lockAnnotations)}
if (tissueAnnotation == null) {
  hierarchy.addPathObjects(annotationsToAdd)
} else {
  tissueAnnotation.addPathObjects(annotationsToAdd)
  hierarchy.fireHierarchyChangedEvent(this, tissueAnnotation)
  if (lockAnnotations)
	tissueAnnotation.setLocked(true)
}

println 'Done!'