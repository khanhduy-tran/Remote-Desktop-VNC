
package org.jfxvnc.app;

import org.jfxvnc.app.persist.SessionContext;
import org.jfxvnc.app.presentation.MainView;
import org.jfxvnc.ui.service.VncRenderService;
import org.slf4j.LoggerFactory;

import com.airhacks.afterburner.injection.Injector;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.StringExpression;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

public class VncClientApp extends Application {

  private final static org.slf4j.Logger logger = LoggerFactory.getLogger(VncClientApp.class);

  private final StringProperty headerProperty = new SimpleStringProperty(System.getProperty("javafx.runtime.version"));
  private final StringExpression headerExpr = Bindings.format("JavaFX VNC Viewer (%s)", headerProperty);

  private final DoubleProperty sceneWidthProperty = new SimpleDoubleProperty(1024);
  private final DoubleProperty sceneHeightProperty = new SimpleDoubleProperty(768);

  private Image offlineImg;
  private Image onlineImg;

  private Stage stageRef;

  @Override
  public void start(Stage stage) throws Exception {
    stageRef = stage;
    stage.titleProperty().bind(headerExpr);
    stage.setResizable(true);
    offlineImg = new Image(VncClientApp.class.getResourceAsStream("icon.png"));
    onlineImg = new Image(VncClientApp.class.getResourceAsStream("icon_green.png"));

    Injector.setLogger(logger::trace);

    VncRenderService vncService = Injector.instantiateModelOrService(VncRenderService.class);

    vncService.fullSceenProperty().addListener((l, a, b) -> Platform.runLater(() -> stage.setFullScreen(b)));
    vncService.restartProperty().addListener(l -> restart());
    vncService.connectInfoProperty().addListener((l, a, b) -> Platform.runLater(() -> headerProperty.set(b.getServerName())));
    vncService.onlineProperty().addListener((l, a, b) -> Platform.runLater(() -> {
      stage.getIcons().add(b ? onlineImg : offlineImg);
      stage.getIcons().remove(!b ? onlineImg : offlineImg);
    }));

    // update property on exit full screen by key combination
    stage.fullScreenProperty().addListener((l, a, b) -> vncService.fullSceenProperty().set(b));

    SessionContext session = Injector.instantiateModelOrService(SessionContext.class);
    session.setSession("jfxvnc.app");
    session.loadSession();

    session.bind(sceneWidthProperty, "scene.width");
    session.bind(sceneHeightProperty, "scene.height");

    MainView main = new MainView();

    final Scene scene = new Scene(main.getView(), sceneWidthProperty.get(), sceneHeightProperty.get());
    stage.setOnCloseRequest((e) -> {
      sceneWidthProperty.set(scene.getWidth());
      sceneHeightProperty.set(scene.getHeight());
      Injector.forgetAll();
      System.exit(0);
    });
    stage.setScene(scene);
    stage.getIcons().add(offlineImg);
    stage.show();
  }

  private void restart() {
    stageRef.close();
    try {
      sceneWidthProperty.set(stageRef.getScene().getWidth());
      sceneHeightProperty.set(stageRef.getScene().getHeight());
      Injector.forgetAll();
      start(new Stage());
    } catch (Exception e) {
      throw new RuntimeException("Failed to restart " + getClass().getName(), e);
    }
  }

  public static void main(String[] args) {
    launch(args);
  }
}
