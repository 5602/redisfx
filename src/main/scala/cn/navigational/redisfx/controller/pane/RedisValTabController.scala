package cn.navigational.redisfx.controller.pane

import cn.navigational.redisfx.AbstractFXMLController
import cn.navigational.redisfx.assets.RedisFxResource
import cn.navigational.redisfx.controller.RedisFxPaneController
import cn.navigational.redisfx.controls.RedisValTab
import cn.navigational.redisfx.enums.{RedisDataType, RedisDataViewFormat}
import cn.navigational.redisfx.helper.NotificationHelper
import cn.navigational.redisfx.util.{JSONUtil, RedisDataUtil}
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.fxml.FXML
import javafx.scene.control.{ChoiceBox, Label, TextArea, TextField}
import javafx.scene.layout.BorderPane

import java.nio.charset.Charset
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Failure, Success}


class RedisValTabController(private val valTab: RedisValTab) extends AbstractFXMLController[BorderPane](RedisFxResource.load("fxml/pane/RedisValTabPane.fxml")) {
  @FXML
  private var tLabel: Label = _
  @FXML
  private var textArea: TextArea = _
  @FXML
  private var dataSize: Label = _
  @FXML
  private var dataFormat: Label = _
  @FXML
  private var keyTextF: TextField = _
  @FXML
  private var dataViewFormat: ChoiceBox[String] = _
  private val viewDataTypeListener: ChangeListener[String] = (_, _, newVal) => {
    this.initVal(RedisDataViewFormat.getViewFormat(newVal))
  }

  {
    initVal()
    for (item <- RedisDataViewFormat.values()) {
      dataViewFormat.getItems.add(item.getName)
    }
    dataViewFormat.getSelectionModel.select(RedisDataViewFormat.PLAINT_TEXT.getName)
    dataViewFormat.getSelectionModel.selectedItemProperty().addListener(this.viewDataTypeListener)
    //移出监听程序
    this.valTab.setOnCloseRequest(_ => dataViewFormat.getSelectionModel.selectedItemProperty().removeListener(this.viewDataTypeListener))
  }

  @FXML
  private def renameKey(): Unit = {

  }

  @FXML
  private def deleteKey(): Unit = {
    val confirm = NotificationHelper.showConfirmAlert(msg = "你确定要删除?")
    if (!confirm) {
      return
    }
    val promise = showLoad[Boolean]("删除中...", showError = false)
    val future = valTab.deleteKey()
    future.onComplete(ar => {
      if (ar.isFailure) {
        showError("删除失败", ar.failed.get)
      } else {
        promise.success(ar.get)
      }
    })
  }

  @FXML
  private def updateTTL(): Unit = {
    val optional = NotificationHelper.showInputDialog("请输入新的TTL值")
    if (optional.isEmpty || optional.get().trim == "") {
      return
    }
    val ttl = optional.get().toInt
    val promise = showLoad[Long]("更新TTL中...", "更新TTL出错")
    val client = RedisFxPaneController.getRedisClient(valTab.uuid)
    val future = client.setTtl(valTab.redisKey, ttl, valTab.index)
    future.onComplete({
      case Success(value) => Platform.runLater(() => {
        this.tLabel.setText(ttl.toString)
        promise.success(value)
      })
      case Failure(ex) => promise.failure(ex)
    })
  }

  private def updateText(text: String, ttl: Long, viewFormat: RedisDataViewFormat = null, format: RedisDataType): Unit = {
    val size = text.getBytes(Charset.forName("UTF8")).length
    var formatVal = text
    var tempFormat = viewFormat
    //如果未指定数据格式=>自动判断
    if (viewFormat == null) {
      tempFormat = RedisDataUtil.getRedisDataViewFormat(text)
    }
    formatVal = RedisDataUtil.formatViewData(text, tempFormat)

    Platform.runLater(() => {
      this.textArea.setText(formatVal)
      this.tLabel.setText(s"TTL:$ttl")
      this.dataSize.setText(s"$size 字节")
      this.keyTextF.setText(valTab.redisKey)
      this.dataFormat.setText(format.getName)
      this.dataViewFormat.getSelectionModel.select(tempFormat.getName)
    })
  }

  @FXML
  def reloadVal(): Unit = {
    this.initVal()
  }

  /**
   * 加载value值
   *
   */
  def initVal(viewFormat: RedisDataViewFormat = null): Future[Unit] = Future {
    val promise = showLoad[Unit]()
    try {
      val client = RedisFxPaneController.getRedisClient(valTab.uuid)
      val ttl = Await.result[Long](client.ttl(valTab.redisKey, valTab.index), Duration.Inf)
      val dataType = Await.result[RedisDataType](client.typeKey(valTab.redisKey, valTab.index), Duration.Inf)
      val text = dataType match {
        case RedisDataType.HASH => Await.result[String](client.hGet(valTab.redisKey, valTab.index), Duration.Inf)
        case RedisDataType.LIST =>
          val len = Await.result[Long](client.lLen(valTab.redisKey, valTab.index), Duration.Inf)
          val list = Await.result[Array[String]](client.lRange(valTab.redisKey, 0, len, valTab.index), Duration.Inf)
          RedisDataUtil.formatListVal(list)
        case RedisDataType.SET =>
          val arr = Await.result[Array[String]](client.sMember(valTab.redisKey, valTab.index), Duration.Inf)
          JSONUtil.objToJsonStr(arr)
        case RedisDataType.Z_SET =>
          val len = Await.result[Long](client.zCard(valTab.redisKey, valTab.index), Duration.Inf)
          val arr = Await.result[Array[String]](client.zRange(valTab.redisKey, 0, len, valTab.index), Duration.Inf)
          RedisDataUtil.formatListVal(arr)
        case _ => Await.result[String](client.get(valTab.redisKey, valTab.index), Duration.Inf)
      }
      this.updateText(text, ttl, viewFormat, dataType)
      promise.success()
    } catch {
      case ex: Exception => promise.failure(ex)
    }
  }

}
