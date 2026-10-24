package com.homework.processor.ui;

import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

/**
 * MainApp is a minimal JavaFX application to verify the build and runtime setup.
 */
public class MainApp extends Application {
    @Override
    public void start(Stage primaryStage) {
        Label label = new Label("Parallel Image Processor - Build Verified!");
        StackPane root = new StackPane(label);
        Scene scene = new Scene(root, 400, 200);

        primaryStage.setTitle("Build Test");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
