#!/usr/bin/env python
from xml.etree import ElementTree as ET
file = open("icon.svg")
element = ET.XML(file.read())
for subelement in element:
    for i in subelement.attrib.keys():
        if "inkscape" in i and "label" in i:
            if subelement.attrib[i] == "Taskbar_v11":
                subelement.attrib["style"] = "display:inline"
            else:
                subelement.attrib["style"] = "display:none"

fileout = open("generated/icon-gen-taskbarv11.svg", "w")
fileout.write(ET.tostring(element))
