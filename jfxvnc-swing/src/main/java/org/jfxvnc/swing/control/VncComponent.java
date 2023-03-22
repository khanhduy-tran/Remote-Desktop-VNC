/*******************************************************************************
 * Copyright (c) 2016 comtel inc.
 *
 * Licensed under the Apache License, version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 *******************************************************************************/
package org.jfxvnc.swing.control;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.image.BufferedImage;
import java.awt.image.DataBuffer;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.awt.image.SampleModel;
import java.awt.image.VolatileImage;
import java.awt.image.WritableRaster;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import javax.swing.JComponent;

import io.netty.buffer.ByteBuf;

public class VncComponent extends JComponent {

  private static final long serialVersionUID = 5723358407311608532L;
  protected BufferedImage currentImage = null;
  private final Lock bufferLock = new ReentrantLock();

  private final RenderComponent renderComponent;
  private boolean keepAspect = true;
  private int imgWidth, imgHeight;
  private final boolean resizable;
  private Rectangle fixBounds;

  private VolatileImage volatileImage;
  private final boolean useVolatile;
  private volatile boolean updatePending = false;
  
  public VncComponent() {
    this(true, true);
  }

  public VncComponent(boolean useVolatile, boolean resizable) {

    this.useVolatile = useVolatile;
    this.resizable = resizable;
    this.imgWidth = 0;
    this.imgHeight = 0;

    if (resizable) {
      setLayout(null);
    }
    add(renderComponent = new RenderComponent());

    if (resizable) {
      renderComponent.addPropertyChangeListener("preferredSize", (evt) -> {
        scaleVideoOutput();
      });
    }

    if (resizable) {
      addComponentListener(new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent evt) {
          scaleVideoOutput();
        }
      });
    }
    renderComponent.setBounds(getBounds());
    setOpaque(false);
  }

  private void scaleVideoOutput() {
    if (fixBounds != null) {
      renderComponent.setBounds(fixBounds);
      return;
    }

    final Component child = renderComponent;
    final Dimension childSize = child.getPreferredSize();
    final int width = getWidth(), height = getHeight();

    double aspect = keepAspect ? (double) childSize.width / (double) childSize.height : 1.0f;

    int scaledHeight = (int) ((double) width / aspect);
    if (!keepAspect) {
      child.setBounds(child.getX(), child.getY(), width, height);
    } else if (scaledHeight < height) {
      final int y = (height - scaledHeight) / 2;
      child.setBounds(0, y, width, scaledHeight);
    } else {
      final int scaledWidth = (int) ((double) height * aspect);
      final int x = (width - scaledWidth) / 2;
      child.setBounds(x, 0, scaledWidth, height);
    }
  }

  public boolean isKeepAspect() {
    return keepAspect;
  }

  public boolean isResizable() {
    return resizable;
  }

  public void setKeepAspect(boolean keepAspect) {
    this.keepAspect = keepAspect;
  }

  @Override
  public boolean isLightweight() {
    return true;
  }

  @Override
  protected void paintComponent(Graphics g) {
  }
  
  private void renderVolatileImage(int x, int y, int w, int h) {
    do {
      final GraphicsConfiguration gc = getGraphicsConfiguration();
      if (volatileImage == null || volatileImage.getWidth() != imgWidth || volatileImage.getHeight() != imgHeight
          || volatileImage.validate(gc) == VolatileImage.IMAGE_INCOMPATIBLE) {

        if (volatileImage != null) {
          volatileImage.flush();
        }

        volatileImage = gc.createCompatibleVolatileImage(w, h);
        volatileImage.setAccelerationPriority(1.0f);
      }
      Graphics2D g = volatileImage.createGraphics();
      g.drawImage(currentImage, x, y, x + w, y + h, x, y, x + w, y + h, null);
      g.dispose();
    } while (volatileImage.contentsLost());
  }

  private void render(Graphics g, int x, int y, int w, int h) {
    if (useVolatile) {
      volatileRender(g, x, y, w, h);
    } else {
      updatePending = false;
      g.drawImage(currentImage, x, y, w, h, null);
    }
  }

  private void volatileRender(Graphics g, int x, int y, int w, int h) {
    do {
      if (updatePending || volatileImage == null || volatileImage.validate(getGraphicsConfiguration()) != VolatileImage.IMAGE_OK) {
        bufferLock.lock();
        try {
          updatePending = false;
          renderVolatileImage(x, y, w, h);
        } finally {
          bufferLock.unlock();
        }
      }
      g.drawImage(volatileImage, x, y, w, h, null);
    } while (volatileImage.contentsLost());
  }
  
  protected final void update(final int x, final int y, final int width, final int height) {
    
      if (currentImage.getWidth() != imgWidth || currentImage.getHeight() != imgHeight) {
        imgWidth = currentImage.getWidth();
        imgHeight = currentImage.getHeight();

        setPreferredSize(new Dimension(imgWidth, imgHeight));
        renderComponent.setPreferredSize(getPreferredSize());
        if (!resizable) {
          setSize(getPreferredSize());
          setMinimumSize(getPreferredSize());
          setMaximumSize(getPreferredSize());
          renderComponent.setSize(getPreferredSize());
          renderComponent.setMinimumSize(getPreferredSize());
          renderComponent.setMaximumSize(getPreferredSize());
        }

      }
      if (renderComponent.isVisible()) {
        renderComponent.repaint(x, y, width, height);
      }
  }

  protected BufferedImage getBufferedImage(int width, int height, int type) {
    if (currentImage != null && currentImage.getWidth() == width && currentImage.getHeight() == height && currentImage.getType() == type) {
      return currentImage;
    }
    if (currentImage != null) {
      currentImage.flush();
    }

    currentImage = new BufferedImage(width, height, type);
    currentImage.setAccelerationPriority(1.0f);
    return currentImage;
  }

  protected BufferedImage getBufferedImage(int width, int height, IndexColorModel colorModel) {
    if (currentImage != null && currentImage.getWidth() == width && currentImage.getHeight() == height
        && currentImage.getType() == BufferedImage.TYPE_BYTE_INDEXED) {
      return currentImage;
    }
    if (currentImage != null) {
      currentImage.flush();
    }
    currentImage = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_INDEXED, colorModel);
    currentImage.setAccelerationPriority(1.0f);
    return currentImage;
  }


  protected void renderFrame(int x, int y, int width, int height, ByteBuf img) {

    final WritableRaster raster = currentImage.getRaster();
    if (img.hasArray()) {
      byte[] data = new byte[img.readableBytes()];
      System.arraycopy(img.array(), img.arrayOffset(), data, 0, data.length);
      raster.setDataElements(x, y, width, height, data);
    } else {
//      byte[] pixels = new byte[img.readableBytes()];
//      img.readBytes(pixels);
//      raster.setDataElements(x, y, width, height, pixels);
      updateRaster(raster, x - raster.getSampleModelTranslateX(), y - raster.getSampleModelTranslateY(), width, height , img);
    }
    updatePending = true;
    update(x, y, width, height);
    //SwingUtilities.invokeLater(() -> update(x, y, width, height));
  }

  
  private void updateRaster(Raster raster, int x, int y, int w, int h, ByteBuf img) {

    final DataBuffer data = raster.getDataBuffer();
    final SampleModel model = raster.getSampleModel();
    int x1 = x + w;
    int y1 = y + h;

    byte[] buffer = new byte[model.getNumDataElements()];

    for (int i = y; i < y1; i++) {
      for (int j = x; j < x1; j++) {
        img.readBytes(buffer);
        model.setDataElements(j, i, buffer, data);
      }
    }
  }

  public void clear() {
    renderComponent.removeAll();
  }

  public void setFixBounds(int x, int y, int width, int height) {
    fixBounds = new Rectangle(x, y, width, height);
  }

  private class RenderComponent extends JComponent {

    private static final long serialVersionUID = -863557731953632418L;

    @Override
    protected void paintComponent(Graphics g) {
      int width = getWidth(), height = getHeight();
      Graphics2D g2d = (Graphics2D) g;
      if (currentImage != null) {
        render(g2d, 0, 0, width, height);
      } else {
        g2d.setColor(getBackground());
        g2d.fillRect(0, 0, width, height);
      }
      g2d.dispose();
    }

    @Override
    public boolean isOpaque() {
      return VncComponent.this.isOpaque();
    }

    @Override
    public boolean isLightweight() {
      return true;
    }
  }

}
