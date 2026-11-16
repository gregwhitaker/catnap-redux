#!/usr/bin/env python3
"""Rebuild Catnap's brand guide and application artwork from the supplied logo.

Install source/requirements.txt, then run this file from an unpacked packet.
Use --packet PATH to select a packet folder. The source logo is never modified.
"""
import argparse
import json
import shutil
import subprocess
import tempfile
from pathlib import Path
from xml.sax.saxutils import escape

from fontTools.ttLib import TTFont as FontToolsFont
from fontTools.varLib.instancer import instantiateVariableFont
from reportlab.pdfgen import canvas
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.lib.colors import HexColor
from reportlab.lib.styles import ParagraphStyle
from reportlab.platypus import Paragraph
from pypdf import PdfReader


INK = '#1D1D1C'
PAPER = '#F6F3EB'
MOSS = '#34574C'
SAGE = '#DDE7DE'
LILAC = '#DCD8EE'
STONE = '#686A63'
WHITE = '#FFFFFF'
LINE = '#D6D8CF'
W, H = 960, 640
M = 48
PALETTE = [('Ink', INK), ('Paper', PAPER), ('Moss', MOSS),
           ('Sage', SAGE), ('Lilac', LILAC), ('Stone', STONE)]


def luminance(value):
    rgb = [int(value[i:i+2], 16) / 255 for i in (1, 3, 5)]
    linear = [x / 12.92 if x <= .04045 else ((x + .055) / 1.055) ** 2.4 for x in rgb]
    return sum(x * k for x, k in zip(linear, (.2126, .7152, .0722)))


def contrast(a, b):
    x, y = sorted([luminance(a), luminance(b)], reverse=True)
    return (x + .05) / (y + .05)


class Page:
    def __init__(self, c, w=W, h=H):
        self.c, self.w, self.h = c, w, h

    def rect(self, x, y, w, h, fill=PAPER, radius=0, stroke=None, lw=.7):
        c = self.c
        c.setFillColor(HexColor(fill))
        c.setStrokeColor(HexColor(stroke or fill))
        c.setLineWidth(lw)
        if radius:
            c.roundRect(x, self.h-y-h, w, h, radius, fill=1, stroke=bool(stroke))
        else:
            c.rect(x, self.h-y-h, w, h, fill=1, stroke=bool(stroke))

    def line(self, x1, y1, x2, y2, color=LINE, width=.8, dash=None):
        c = self.c
        c.saveState()
        c.setStrokeColor(HexColor(color)); c.setLineWidth(width)
        if dash: c.setDash(*dash)
        c.line(x1, self.h-y1, x2, self.h-y2)
        c.restoreState()

    def text(self, s, x, y, size=14, font='Body', color=INK):
        self.c.setFillColor(HexColor(color)); self.c.setFont(font, size)
        self.c.drawString(x, self.h-y-size*.82, s)

    def para(self, s, x, y, w, size=14, leading=None, color=INK, font='Body'):
        style = ParagraphStyle('p', fontName=font, fontSize=size, leading=leading or size*1.45,
                               textColor=HexColor(color), spaceAfter=0)
        p = Paragraph(escape(s).replace('\n', '<br/>'), style)
        _, h = p.wrap(w, self.h)
        if y+h > self.h-38:
            raise ValueError(f'Paragraph exceeds safe page bounds: {s[:60]} at {y+h}')
        p.drawOn(self.c, x, self.h-y-h)
        return h

    def label(self, s, x, y, color=MOSS, size=10):
        self.text(s.upper(), x, y, size, 'MonoMedium', color)

    def logo(self, x, y, width):
        self.c.drawImage(str(LOGO), x, self.h-y-width/3,
                         width=width, height=width/3, mask='auto')

    def pill(self, s, x, y, fill=SAGE, color=MOSS, size=10):
        width=pdfmetrics.stringWidth(s, 'MonoMedium', size)+24
        self.rect(x,y,width,26,fill,13)
        self.text(s,x+12,y+8,size,'MonoMedium',color)
        return width

    def code(self, lines, x, y, w, h, title=None, size=14, fill=INK):
        self.rect(x,y,w,h,fill,16)
        if title: self.label(title,x+22,y+20,color=SAGE,size=9)
        top=y+(48 if title else 22)
        for i,s in enumerate(lines): self.text(s,x+22,top+i*size*1.6,size,'Mono',PAPER)

    def frame(self, n, chapter, title, subtitle=None, dark=False, footer=True):
        self.rect(0,0,self.w,self.h,INK if dark else PAPER)
        self.label('CATNAP  /  BRAND GUIDE',M,30,color=SAGE if dark else MOSS)
        self.label(chapter,696,30,color=SAGE if dark else MOSS)
        self.text(title,M,75,36,'Bold',PAPER if dark else INK)
        if subtitle: self.para(subtitle,M,129,830,13.5,color=SAGE if dark else STONE)
        if footer: self.footer(n, dark)

    def footer(self,n,dark=False):
        self.line(M,600,W-M,600,color=MOSS if dark else LINE)
        self.text('CATNAP  /  IDENTITY SYSTEM 1.0',M,613,8,'Mono',SAGE if dark else STONE)
        self.text(f'{n:02d} / 12',861,613,8,'Mono',SAGE if dark else STONE)


def draw_social(p):
    p.rect(0,0,1200,630,PAPER)
    p.logo(58,38,318)
    p.label('JSON PROJECTION FOR THE JVM',64,186,size=12)
    p.text('Just the JSON',60,236,68,'Bold')
    p.text('you need.',60,318,68,'Bold')
    p.para('Select the fields.\nKeep your response focused.',64,440,520,23,32,color=STONE)
    p.rect(746,64,390,502,SAGE,30)
    p.label('SELECT',778,104)
    p.rect(774,136,334,58,PAPER,14)
    p.text('name,price(amount)',793,156,18,'MonoMedium',MOSS)
    p.code(['{','  "name": "Field notebook",','  "price": {','    "amount": 12','  }','}'],774,222,334,268,'PROJECTED JSON',16)
    p.label('CALM BY DESIGN. PRECISE BY NATURE.',64,574,size=10)


def draw_banner(p):
    p.rect(0,0,1600,400,PAPER)
    p.logo(58,91,555)
    p.line(685,72,685,328,LINE,2)
    p.label('JSON PROJECTION FOR THE JVM',750,80,size=13)
    p.text('Just the JSON',747,126,64,'Bold')
    p.text('you need.',747,203,64,'Bold')
    p.pill('name,price(amount)',751,305,SAGE,MOSS,16)


def draw_square(p):
    p.rect(0,0,1080,1080,SAGE)
    p.logo(64,50,390)
    p.label('JSON PROJECTION FOR THE JVM',72,266,size=14)
    p.text('Keep your',64,324,100,'Bold')
    p.text('response',64,440,100,'Bold')
    p.text('focused.',64,556,100,'Bold')
    p.rect(68,744,944,208,PAPER,28)
    p.label('SELECT THE FIELDS YOU NEED',100,779,size=14)
    p.text('name,price(amount)',98,829,46,'MonoMedium',MOSS)
    p.text('Just the JSON you need.',72,1014,22,'Medium',MOSS)


def embed_art(p, draw, x, y, width, orig_w, orig_h):
    p.c.saveState()
    scale=width/orig_w
    p.c.translate(x,p.h-y-orig_h*scale)
    p.c.scale(scale,scale)
    draw(Page(p.c,orig_w,orig_h))
    p.c.restoreState()


def draw_quick_reference(p, standalone=False):
    p.frame(11,'QUICK REFERENCE' if standalone else '10 / QUICK REFERENCE',
            'The system at a glance.',footer=not standalone)
    p.logo(43,153,416)
    p.text('Just the JSON you need.',M,317,27,'Bold',MOSS)
    p.text('JSON projection for the JVM.',M,353,16,'Medium',STONE)
    p.line(M,398,458,398)
    p.label('LOGO',M,420)
    p.para('Original artwork. Light backgrounds. Clear space = 1/4 of visible logo height. Minimum width = 180 px / 40 mm.',M,447,406,13.5,21)
    p.label('VOICE',M,527)
    p.text('Calm. Precise. Helpful.',M,553,18,'SemiBold')
    for i,(name,col) in enumerate(PALETTE):
        x=535+(i%3)*129; y=169+(i//3)*112
        p.rect(x,y,112,56,col,9,LINE if col==PAPER else None)
        p.text(name,x,y+65,11,'SemiBold')
        p.text(col,x,y+83,9,'Mono',STONE)
    p.label('TYPE',535,409)
    p.text('Manrope',535,439,25,'SemiBold')
    p.text('IBM Plex Mono',535,478,18,'Mono',MOSS)
    p.label('LAYOUT',535,527)
    p.text('8 px base / 24-48 px gaps / 16-24 px corners',535,553,10,'Mono',STONE)
    if standalone:
        p.line(M,600,W-M,600)
        p.text('CATNAP  /  QUICK REFERENCE 1.0',M,613,8,'Mono',STONE)
        p.text('NOVEMBER 2026',817,613,8,'Mono',STONE)


def guide(path):
    c=canvas.Canvas(str(path),pagesize=(W,H))
    c.setTitle('Catnap Brand Guide'); c.setAuthor('Catnap')
    c.setSubject('Brand identity, logo use, color, typography, voice and applications')
    p=Page(c)

    # 01: Cover
    p.rect(0,0,W,H,PAPER)
    p.label('BRAND GUIDE  /  01',M,32)
    p.label('NOVEMBER 2026',770,32)
    p.logo(42,82,455)
    p.text('Just the JSON',45,291,58,'Bold')
    p.text('you need.',45,359,58,'Bold')
    p.para('A calm, clear identity for a focused\nJVM JSON projection library.',M,461,450,17,25,color=STONE)
    p.rect(588,86,324,464,SAGE,26)
    p.label('THE IDEA',615,113)
    p.text('Keep what matters.',613,153,23,'SemiBold',MOSS)
    p.rect(611,205,278,53,PAPER,12)
    p.text('name,price(amount)',628,224,14,'MonoMedium',MOSS)
    p.code(['{','  "name": "Field notebook",','  "price": {','    "amount": 12','  }','}'],611,280,278,231,'PROJECTED JSON',12.8)
    p.footer(1); c.showPage()

    # 02: Foundation
    p.frame(2,'01 / FOUNDATION','Calm by design. Precise by nature.',
            'The sleeping cat brings warmth. The product story stays concrete and useful.')
    p.label('POSITIONING',M,193)
    p.para('Catnap helps JVM developers select the fields they need from JSON, with a focused projection core and separate integrations.',M,219,534,24,34,font='Medium')
    p.rect(641,185,271,168,SAGE,18)
    p.label('PRIMARY MESSAGE',665,207)
    p.para('Just the JSON\nyou need.',665,243,225,29,36,font='Bold',color=MOSS)
    cols=[('01','Calm','Use breathing room, warm surfaces and measured language. Make complexity feel manageable.'),
          ('02','Precise','Name the behavior. Show a real query and result. Keep technical claims tied to the release.'),
          ('03','Helpful','Lead with the next useful step. Explain the smallest concept that helps a developer proceed.')]
    for i,(num,title,body) in enumerate(cols):
        x=M+i*296
        p.line(x,391,x+272,391)
        p.label(num,x,410)
        p.text(title,x,442,23,'SemiBold')
        p.para(body,x,480,260,13.5,20,color=STONE)
    c.showPage()

    # 03: Master logo
    p.frame(3,'02 / LOGO','A small moment of quiet.',
            'The approved sleeping-cat mark and lowercase wordmark form one horizontal signature.')
    p.rect(M,170,864,280,WHITE,20)
    p.logo(180,212,600)
    p.label('APPROVED MASTER',M,461)
    p.para('Use the supplied transparent PNG. Preserve the shape, color, letterforms and spacing of the original artwork.',M,488,401,14.5,22)
    p.label('NAMING',510,461)
    p.para('Write Catnap in prose and catnap in code or package names. The lowercase lettering in the logo is fixed artwork, not a font to retype.',510,488,394,14.5,22)
    c.showPage()

    # 04: Geometry
    p.frame(4,'03 / LOGO RULES','Give the cat room to breathe.',
            'Clear space protects recognition. Always scale the complete logo proportionally.')
    # Measured visible-art bounds in the 2172 x 724 source: (41, 21, 2116, 680).
    x,y,ww=111,228,472
    hh=ww/3
    artx=x+41/2172*ww; arty=y+21/724*hh
    artw=2075/2172*ww; arth=659/724*hh; clear=arth/4
    p.rect(70,181,554,275,WHITE,18)
    p.logo(x,y,ww)
    for xx in (artx-clear, artx+artw+clear): p.line(xx,arty-clear,xx,arty+arth+clear,MOSS,.8,(3,3))
    for yy in (arty-clear,arty+arth+clear): p.line(artx-clear,yy,artx+artw+clear,yy,MOSS,.8,(3,3))
    p.line(artx,arty,artx+artw,arty,LINE,.7,(2,3))
    p.line(artx,arty+arth,artx+artw,arty+arth,LINE,.7,(2,3))
    p.label('x',artx+artw+10,arty-clear+12,size=9)
    p.label('H',artx+artw+13,arty+arth/2,size=9)
    p.text('x = 1/4 of visible artwork height (H)',94,422,11,'Mono',MOSS)
    p.label('CLEAR SPACE',671,191)
    p.para('Keep at least x free on all sides, measured from the visible artwork. Source-file padding is not the full clear-space allowance.',671,217,235,13.5,20)
    p.label('MINIMUM SIZE',671,326)
    p.text('180 px wide',671,356,23,'SemiBold')
    p.text('40 mm wide in print',671,390,16,'Medium',STONE)
    p.para('Measure the full supplied PNG. Recheck legibility at the final viewing size.',671,421,231,11.5,17,color=STONE)
    p.line(M,491,912,491)
    p.label('PRESERVE',M,514)
    p.para('Proportion, color, spacing and the full signature.',M,541,248,12.5,color=STONE)
    p.label('AVOID',344,514)
    p.para('Stretching, rotation, shadows, outlines or retyping.',344,541,244,12.5,color=STONE)
    p.label('KEEP IT SIMPLE',640,514)
    p.para('No extra zzz, taglines inside the lockup or busy imagery.',640,541,264,12.5,color=STONE)
    c.showPage()

    # 05: Background use and production constraints
    p.frame(5,'04 / LOGO PLACEMENT','One logo. A few good homes.',
            'Use the original dark artwork on light, quiet surfaces. Give dark layouts a light logo panel.')
    for x,y,bg,label in [(48,187,PAPER,'PAPER / PRIMARY'),(490,187,SAGE,'SAGE / SECONDARY')]:
        p.rect(x,y,422,167,bg,16,LINE)
        p.logo(x+62,y+20,298)
        p.label(label,x+22,y+139,size=8.5)
    p.rect(48,377,422,174,INK,16)
    p.rect(88,393,342,121,PAPER,14)
    p.logo(133,411,252)
    p.label('DARK LAYOUT / LIGHT PANEL',70,525,color=SAGE,size=8.5)
    p.label('MASTER FORMAT',514,382)
    p.para('2172 x 724 px, transparent RGBA PNG. The original artwork is preserved exactly in this packet.',514,410,374,13.5,20)
    p.label('PRODUCTION NOTE',514,471)
    p.para('At 300 ppi, the full image is 184 mm wide. Use a professionally redrawn vector master for larger print production; this packet contains raster logo artwork.',514,499,374,12.5,19,color=STONE)
    c.showPage()

    # 06: Color
    p.frame(6,'05 / COLOR','Warm neutrals. A quiet green.',
            'Paper carries the system. Ink handles reading. Moss adds focus; soft tints give content room.')
    roles=['Text and logo tone','Main canvas','Links and actions','Panels and selected areas','Small editorial accents','Secondary text']
    for i,((name,col),role) in enumerate(zip(PALETTE,roles)):
        x=M+i*147
        p.rect(x,186,129,138,col,14,LINE if col==PAPER else None)
        p.text(name,x,342,16,'SemiBold')
        p.text(col,x,371,12,'Mono',STONE)
        p.para(role,x,400,129,11.5,16,color=STONE)
    p.line(M,453,912,453)
    p.label('READABLE PAIRS',M,475)
    pairs=[('Ink / Paper',INK,PAPER),('Moss / Paper',MOSS,PAPER),('Paper / Moss',PAPER,MOSS),('Stone / Paper',STONE,PAPER)]
    for i,(name,fg,bg) in enumerate(pairs):
        x=M+i*221
        p.rect(x,500,203,49,bg,10,LINE if bg==PAPER else None)
        p.text(f'{name}  {contrast(fg,bg):.2f}:1',x+12,516,10.2,'Mono',fg)
    p.text('All four pairs meet 4.5:1 for normal text. Use Ink on Sage and Lilac; tints are backgrounds.',M,568,11,'Body',STONE)
    c.showPage()

    # 07: Type
    p.frame(7,'06 / TYPOGRAPHY','Friendly shape. Technical clarity.',
            'Manrope carries the story. IBM Plex Mono carries code, labels and values.')
    p.label('MANROPE',M,189)
    p.text('Aa Bb Cc',M,220,66,'SemiBold')
    p.text('Clear. Quiet. Useful.',M,309,29,'Medium')
    p.para('Use regular 400 for body copy, medium 500 for emphasis, semibold 600 for headings and bold 700 for display.',M,361,395,14,21,color=STONE)
    p.label('IBM PLEX MONO',M,461)
    p.text('name,price(amount)',M,493,23,'MonoMedium',MOSS)
    p.text('{ "amount": 12 }',M,533,19,'Mono',STONE)
    p.line(486,185,486,562)
    p.label('DIGITAL TYPE SCALE',535,189)
    scale=[('Display','64 / 70','700'),('Page heading','40 / 48','600'),('Section heading','28 / 36','600'),('Body','16 / 25','400'),('Code','14 / 22','400'),('Caption / label','12 / 18','500')]
    for i,(role,sizes,weight) in enumerate(scale):
        yy=229+i*42
        p.text(role,535,yy,14,'Medium')
        p.text(sizes+' px',700,yy,12,'Mono',MOSS)
        p.text(weight,865,yy,11,'Mono',STONE)
        p.line(535,yy+28,912,yy+28)
    p.para('Sizes list font size / line height. Use sentence case and left-aligned copy. Keep the supplied logo as artwork.',535,505,367,12.5,19,color=STONE)
    c.showPage()

    # 08: Voice
    p.frame(8,'07 / VOICE','Say the useful thing first.',
            'Sound like a thoughtful developer: direct, composed and specific.')
    p.rect(M,187,406,181,SAGE,18)
    p.label('PRIMARY TAGLINE',72,209)
    p.text('Just the JSON you need.',72,244,26,'Bold',MOSS)
    p.label('ONE-LINE DESCRIPTION',72,303)
    p.text('JSON projection for the JVM.',72,330,17,'Medium')
    p.label('SHORT DESCRIPTION',501,194)
    p.para('Catnap is a JVM JSON projection library for selecting fields from JSON. A focused core and separate integrations help developers keep responses clear and intentional.',501,224,400,17,25)
    examples=[('Lead with behavior.','Select name and the nested price amount.','Skip broad claims such as "Effortless data magic."'),
              ('Make actions concrete.','Read the guide. Try the query. View the example.','Choose descriptive verbs for links and buttons.'),
              ('Keep claims grounded.','Describe the integrations available in the release.','Reserve speed and compatibility claims for evidence.')]
    for i,(title,body,note) in enumerate(examples):
        yy=400+i*58
        p.text(title,M,yy,13.5,'SemiBold')
        p.text(body,286,yy,12.5,'Body')
        p.text(note,286,yy+22,11.5,'Body',STONE)
    c.showPage()

    # 09: Product story
    p.frame(9,'08 / PRODUCT STORY','Show the selection. Then the result.',
            'Use a small, valid example. Let the before-and-after carry the explanation.')
    p.pill('GOOGLE DIALECT  /  name,price(amount)',M,184,size=12)
    p.code(['{','  "name": "Field notebook",','  "price": {','    "amount": 12,','    "currency": "USD"','  },','  "stock": 24','}'],M,239,413,276,'SOURCE JSON',14)
    p.code(['{','  "name": "Field notebook",','  "price": {','    "amount": 12','  }','}'],499,239,413,276,'PROJECTED JSON',14)
    p.para('The query keeps name and price.amount. The projection omits currency and stock. Selected values stay unchanged.',M,541,830,14.5,22,color=MOSS)
    c.showPage()

    # 10: Applications
    p.frame(10,'09 / APPLICATIONS','A consistent first impression.',
            'Ready-to-use graphics repeat the same logo, message, palette and spacing.')
    embed_art(p,draw_social,48,185,545,1200,630)
    p.label('SOCIAL PREVIEW',48,487)
    p.text('1200 x 630 px',48,511,12,'Mono',STONE)
    embed_art(p,draw_square,647,185,265,1080,1080)
    p.label('SQUARE ANNOUNCEMENT',647,471)
    p.text('1080 x 1080 px',647,495,12,'Mono',STONE)
    p.para('Also included: a 1600 x 400 px repository banner. Artwork uses the original logo as supplied.',48,551,823,13,color=STONE)
    c.showPage()

    # 11: Quick reference
    draw_quick_reference(p)
    c.showPage()

    # 12: Handoff
    p.frame(12,'11 / HANDOFF','Everything in one place.',
            'A practical packet for documentation, announcements and future design work.')
    p.label('INCLUDED FILES',M,187)
    entries=[('Guides','12-page guide + standalone quick reference'),
             ('Logo','Original transparent PNG, unchanged'),
             ('Graphics','Social preview, repository banner, square card'),
             ('Typography','Manrope + IBM Plex Mono, with font licenses'),
             ('Tokens','CSS variables, JSON palette and GIMP swatches'),
             ('Source','Editable copy and PDF/application build script')]
    for i,(a,b) in enumerate(entries):
        yy=221+i*47
        p.text(a,M,yy,13.5,'SemiBold')
        p.para(b,167,yy,367,12.5,18,color=STONE)
        p.line(M,yy+33,542,yy+33)
    p.rect(585,185,327,375,SAGE,18)
    p.label('REFERENCE NOTES',609,209)
    refs=[('Project facts','README, core API and query specification in the Catnap repository.'),
          ('Typefaces','Official Google Fonts distributions; SIL Open Font License 1.1 files are bundled.'),
          ('Contrast','Ratios computed from the sRGB palette using the WCAG relative-luminance formula.')]
    for i,(a,b) in enumerate(refs):
        yy=245+i*86
        p.text(a,609,yy,13,'SemiBold',MOSS)
        p.para(b,609,yy+25,275,11.5,17,color=MOSS)
    p.para('Source links and rebuilding instructions are in START-HERE.txt and source/references.txt.',609,507,277,10.5,15,color=MOSS)
    p.text('Version 1.0  /  November 15, 2026',M,553,11,'Mono',STONE)
    c.showPage(); c.save()


def register_fonts(packet, cache):
    variable=packet/'fonts/Manrope-Variable.ttf'
    for alias,weight in [('Body',400),('Medium',500),('SemiBold',600),('Bold',700)]:
        dest=cache/f'Manrope-{weight}.ttf'
        font=FontToolsFont(variable)
        static=instantiateVariableFont(font,{'wght':weight},inplace=True,updateFontNames=True)
        static.save(dest)
        pdfmetrics.registerFont(TTFont(alias,str(dest)))
    for alias,file in [('Mono','IBMPlexMono-Regular.ttf'),('MonoMedium','IBMPlexMono-Medium.ttf')]:
        pdfmetrics.registerFont(TTFont(alias,str(packet/'fonts'/file)))


def write_tokens(packet):
    rgb=lambda h:[int(h[i:i+2],16) for i in (1,3,5)]
    data={'brand':'Catnap','version':'1.0','colorSpace':'sRGB',
          'colors':{n.lower():{'hex':h,'rgb':rgb(h)} for n,h in PALETTE},
          'fonts':{'sans':'Manrope','mono':'IBM Plex Mono'},
          'spacingPx':[8,16,24,32,48,64,96], 'cornerRadiusPx':{'code':16,'panel':24},
          'typeScalePx':{'display':[64,70],'h1':[40,48],'h2':[28,36],'body':[16,25],'code':[14,22],'caption':[12,18]},
          'logo':{'file':'logos/catnap-logo.png','width':2172,'height':724,'minimumWidthPx':180,
                  'minimumWidthMm':40,'clearSpace':'one quarter of visible artwork height on every side'},
          'contrastRatios':{f'{a}On{b}':round(contrast(ha,hb),3) for a,ha in PALETTE for b,hb in PALETTE if a!=b}}
    (packet/'tokens/catnap.json').write_text(json.dumps(data,indent=2)+'\n')
    css='''/* Catnap identity system 1.0. Font licenses are in ../fonts/. */
@font-face {
  font-family: "Manrope";
  src: url("../fonts/Manrope-Variable.ttf") format("truetype");
  font-style: normal;
  font-weight: 200 800;
  font-display: swap;
}
@font-face {
  font-family: "IBM Plex Mono";
  src: url("../fonts/IBMPlexMono-Regular.ttf") format("truetype");
  font-style: normal;
  font-weight: 400;
  font-display: swap;
}
@font-face {
  font-family: "IBM Plex Mono";
  src: url("../fonts/IBMPlexMono-Medium.ttf") format("truetype");
  font-style: normal;
  font-weight: 500;
  font-display: swap;
}
:root {
'''
    css+=''.join(f'  --catnap-{n.lower()}: {h};\n' for n,h in PALETTE)
    css+='''  --catnap-font-sans: "Manrope", system-ui, -apple-system, "Segoe UI", sans-serif;
  --catnap-font-mono: "IBM Plex Mono", ui-monospace, "SFMono-Regular", Consolas, monospace;
  --catnap-space-1: 0.5rem;
  --catnap-space-2: 1rem;
  --catnap-space-3: 1.5rem;
  --catnap-space-4: 2rem;
  --catnap-space-6: 3rem;
  --catnap-space-8: 4rem;
  --catnap-radius-code: 1rem;
  --catnap-radius-panel: 1.5rem;
  --catnap-background: var(--catnap-paper);
  --catnap-text: var(--catnap-ink);
  --catnap-text-muted: var(--catnap-stone);
  --catnap-action: var(--catnap-moss);
  --catnap-action-text: var(--catnap-paper);
  --catnap-display: 700 4rem/1.09375 var(--catnap-font-sans);
  --catnap-heading-1: 600 2.5rem/1.2 var(--catnap-font-sans);
  --catnap-heading-2: 600 1.75rem/1.2857 var(--catnap-font-sans);
  --catnap-body: 400 1rem/1.5625 var(--catnap-font-sans);
  --catnap-code: 400 0.875rem/1.5714 var(--catnap-font-mono);
  --catnap-caption: 500 0.75rem/1.5 var(--catnap-font-sans);
}
'''
    (packet/'tokens/catnap.css').write_text(css)
    swatches='GIMP Palette\nName: Catnap\nColumns: 6\n# sRGB brand colors\n'
    swatches+=''.join(f'{r:3} {g:3} {b:3} {n}\n' for n,h in PALETTE for r,g,b in [rgb(h)])
    (packet/'tokens/catnap.gpl').write_text(swatches)


def main():
    parser=argparse.ArgumentParser()
    default=Path(__file__).resolve().parents[1] if Path(__file__).parent.name=='source' else Path.cwd()/'output/brand/catnap-brand-packet'
    parser.add_argument('--packet',type=Path,default=default)
    parser.add_argument('--pdftoppm',default=shutil.which('pdftoppm'))
    args=parser.parse_args(); packet=args.packet.resolve()
    for d in ('logos','fonts','graphics','tokens','copy','source'): (packet/d).mkdir(parents=True,exist_ok=True)
    global LOGO
    LOGO=packet/'logos/catnap-logo.png'
    with tempfile.TemporaryDirectory(prefix='catnap-brand-') as tmp:
        cache=Path(tmp)
        register_fonts(packet,cache)
        write_tokens(packet)
        guide(packet/'Catnap-Brand-Guide.pdf')
        reader=PdfReader(packet/'Catnap-Brand-Guide.pdf')
        qc=canvas.Canvas(str(packet/'Catnap-Quick-Reference.pdf'),pagesize=(W,H))
        qc.setTitle('Catnap Quick Reference'); qc.setAuthor('Catnap')
        draw_quick_reference(Page(qc),standalone=True)
        qc.showPage(); qc.save()
        jobs=[('catnap-social-1200x630',1200,630,draw_social),
              ('catnap-repository-banner-1600x400',1600,400,draw_banner),
              ('catnap-announcement-1080x1080',1080,1080,draw_square)]
        for name,w,h,draw in jobs:
            pdf=cache/(name+'.pdf'); cc=canvas.Canvas(str(pdf),pagesize=(w,h))
            draw(Page(cc,w,h)); cc.showPage(); cc.save()
            if args.pdftoppm:
                subprocess.run([args.pdftoppm,'-png','-r','72','-singlefile',str(pdf),str(packet/'graphics'/name)],check=True)
            else:
                shutil.copy2(pdf,packet/'graphics'/pdf.name)
        print(json.dumps({'pages':len(reader.pages),'packet':str(packet),'contrast':round(contrast(STONE,PAPER),3)},indent=2))


if __name__=='__main__': main()
