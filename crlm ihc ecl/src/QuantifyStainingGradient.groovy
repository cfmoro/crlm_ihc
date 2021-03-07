/**
 * Script to quantify gradient of a given staining, in principle in a tumor margin annotation, 
 * with the intensity profile averaged horizontally as output in cvs format.
 * @author Pete Bankhead
 * @author Modified by Carlos F Moro and Sara Harrizi
 */

import ij.IJ
import ij.ImagePlus
import ij.gui.Plot
import ij.gui.PolygonRoi
import ij.gui.RotatedRectRoi
import ij.gui.Roi
import ij.measure.Calibration
import ij.plugin.Straightener
import ij.process.ColorProcessor
import ij.process.ImageProcessor
import qupath.lib.images.ImageData
import qupath.lib.images.servers.TransformedServerBuilder
import qupath.imagej.gui.IJExtension
import qupath.lib.common.GeneralTools
import qupath.lib.regions.RegionRequest
import qupath.lib.roi.PolygonROI
import qupath.lib.gui.scripting.QPEx
import qupath.imagej.tools.IJTools
import ij.gui.ProfilePlot
import java.awt.event.KeyEvent

// Define the resolution to run at (can lower this cautiously...)
double requestedPixelSizeMicrons = 2

// Width of line in pixels (related to the requestedPixelSizeMicrons)
// TODO: Cannot uderstand exactly how to set its number so the quantitation objective
//       is achieved and the straighgtened image predictibly created according to it
//int widthPixels = 600/2 // Seems that this defined the width in um in ImageJ??, see below in new Straightener().straighten() line

// Name of the relevant stain
def stainName = 'DAB' // Feel free to change!

// Threshold optical density value to apply when calculating the stained percentage
double threshold = 0.2

// Use the max value for each location along the profile - alternative is the mean
def useMaxProfile = false

//--------------------------------------------------------

// Obtain current image
def ImageData imageData = getCurrentImageData()

// Get the stains & find the index of the one we want
def stains = imageData.getColorDeconvolutionStains()
int stainInd = -1
for (int i = 1; i <= 3; i++) {
	if (stains.getStain(i).getName() == stainName) {
		stainInd = i
		break
	}
}
if (stainInd < 0) {
	print 'Count not find a stain with name ' + stainName
	return
}

// Create an ImageServer that applies color deconvolution to the current image, using the current stains
def server = new TransformedServerBuilder(imageData.getServer())
	.deconvolveStains(imageData.getColorDeconvolutionStains(), stainInd)
	.build()

// Iterate over annotations of interest
for (annotation in getAnnotationObjects().findAll{ it.getPathClass() == getPathClass("Band*") }) {


  // Obtain image region for the annotation ROI and convert to ImagePlus type
  double downsample = requestedPixelSizeMicrons / server.getPixelCalibration().getAveragedPixelSizeMicrons()
  println 'requestedPixelSizeMicrons = ' + requestedPixelSizeMicrons
  println 'server.getPixelCalibration().getAveragedPixelSizeMicrons() = ' + server.getPixelCalibration().getAveragedPixelSizeMicrons()
  println 'downsample = ' + downsample
  
  def region = RegionRequest.createInstance(server.getPath(), downsample, annotation.getROI())
  def pathImage = IJTools.convertToImagePlus(server, region)
  def imp = pathImage.getImage()

  // Create an ImageJ polygon Roi encompassing the entire region
  def roiIJ = IJTools.convertToIJRoi(annotation.getROI(), pathImage) //as PolygonRoi
	// Convert Polygon to a Polyline - closing it if necessary
	def poly = roiIJ.getFloatPolygon()
	if (poly.xpoints[0] != poly.xpoints[-1] || poly.ypoints[0] != poly.ypoints[-1]) {
		print 'Closing polygon now!'
		poly.addPoint(poly.xpoints[0], poly.ypoints[0])
	}
	roiIJ = new PolygonRoi(poly, Roi.POLYLINE)


  // Start up ImageJ & show the image
  IJExtension.getImageJInstance()
  imp.setRoi(roiIJ)
  imp.resetDisplayRange() // What does this do?
  // For the purposes of visualization, show the threshold
  imp.getProcessor().setThreshold(threshold, ImageProcessor.NO_THRESHOLD, ImageProcessor.RED_LUT) // What does this do? How to choose another LUT?
  imp.setTitle("Band: " + imp.getTitle())
  imp.show()

  def bandWidth = annotation.getMeasurementList().getMeasurementValue("band width um")
  println "Band width: " + bandWidth
  def ipStraightened = new Straightener().straighten(imp, roiIJ, (bandWidth/requestedPixelSizeMicrons) as Integer)  // orig widthPixels
  def impStraighted = new ij.ImagePlus("Band: Straightened", ipStraightened)
	
  // Set the calibration if the pixel width & height are similar
	// Print calibration info, what does each param mean?
	def cal = imp.getCalibration()
	println 'cal ' + cal

	if (GeneralTools.almostTheSame(cal.pixelWidth, cal.pixelHeight, 0.001)) {
		println 'Setting calibration...'
		cal = cal.clone() as Calibration
		cal.pixelWidth = cal.pixelWidth/2.0 + cal.pixelHeight/2.0
		cal.pixelHeight = cal.pixelWidth
		impStraighted.setCalibration(cal)
	} else {
		println 'Pixel width & height were different! I did NOT try to set the calibration'
	}
// Show the straightened image
   impStraighted.show()
  
	// Optionally create a plot using ImageJ's Plot Profile (mostly to check ours is correct...)
	//// Plot the profile & display it - note this will always use the mean!!!!!
	impStraighted.setRoi(new Roi(0, 0, impStraighted.getWidth(), impStraighted.getHeight()), true)
   // Holding down the alt key to average horizontally instead of a "column average plot",
	IJ.setKeyDown(KeyEvent.VK_ALT)
	IJ.run(impStraighted, "Plot Profile", "")
	// Intensity values from ImageJ averaged horizontally
	profY = new ProfilePlot(impStraighted, true).getProfile()
	println('profY size: ' + profY.size())
	println('profY: ' + profY) // See calculation of xPlot below for calibrated distances in x-axis
		
	// Calculate our own value, averaged along width
	int nPoints = ipStraightened.getHeight()
	println 'nPoints = ipStraightened.getHeight() ' + nPoints
	println 'ipStraightened.getWidth() ' + ipStraightened.getWidth()
	int nAboveThreshold = 0
	def xPlot = new double[nPoints]
	def yPlot = new double[nPoints]
	for (int y = 0; y < ipStraightened.getHeight(); y++) {
		double maxValue = Double.NEGATIVE_INFINITY
		double sumValue = 0
		for (int x = 0; x < ipStraightened.getWidth(); x++) {
			double val = ipStraightened.getf(x, y)
			if(val == Double.NaN) { // Why beyond certain x, pixel values are NaN??
				  continue
			}
			if (val > maxValue) {
				maxValue = val
			}
			sumValue += val
		}
		double result = useMaxProfile ? maxValue : sumValue / ipStraightened.getWidth()
		xPlot[y] = y * impStraighted.getCalibration().pixelWidth
		yPlot[y] = result
		if (result >= threshold)
			nAboveThreshold++
	}

	println('xPlot size: ' + xPlot.size())
	println('xPlot: ' + xPlot)
	println('yPlot size: ' + yPlot.size())
	println('yPlot: ' + yPlot)
	
	def plot = new Plot('Own plot: Band: intensity profile plot of ' + stainName, 'Distance', stainName +
		' value', xPlot as double[], yPlot as double[])
	plot.show()
	
	// Print (or before store in the annotation) the global quantitation value
	def name = String.format('Stained percentage (width=%d, threshold=%.3f, max=%s) ',
			bandWidth as Integer, threshold, useMaxProfile)
	def percentage = nAboveThreshold * 100.0 / nPoints
		
	println name + percentage
	
	// TODO: Decision needed
	// Intensity values are slightly higher from ImageJ than ours, maybe related to the blank (NaN) regions
	// in the straightened image?
	// Anyway, as we will be using the average intensities and not the maximum, possibly safer and easier
	// to use ImageJ funtion and remove own code that duplicates its function?


	// Export profile as csv, from https://gist.github.com/Svidro/5e4c29630e8d2ef36988184987d1028f#file-csv-file-write-out-data-groovy
	//Reference sample for writing out some array as a CSV file with a header line and row headers (classes)
	//Based off of https://forum.image.sc/t/write-polygon-coordinates-to-text-or-csv-file/44811/2
	def header = "Image name, Annotation name, Distance um, Stain value"

	def path =imageData.getServer().getPath()
	def imageName = path[path.lastIndexOf(':')+1..-1]
	imageName = imageName[imageName.lastIndexOf('/')+1..-1]
		   
	output = "/home/bibu/Workspace/crlm ihc/output/${imageName}.csv"
	File csvFile = new File(output)
	csvFile.createNewFile()

	def annotationName = annotation.getName()
	def outData = []
	for (int x = 0; x < xPlot.size(); x++) {
		 outData.add([imageName, annotationName, xPlot[x], profY[x]]) // alternatively our own yPlot[x]???
	}
	println('outData: ' + outData)
	
	new File(output).withWriter { fw ->
		fw.writeLine(header)
		outData.eachWithIndex{l,x->
			String line = l.join(",")
			fw.writeLine(line)
		}
	}

  break
}

  print 'Done!'