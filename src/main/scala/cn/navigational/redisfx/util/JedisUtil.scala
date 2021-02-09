package cn.navigational.redisfx.util

import cn.navigational.redisfx.enums.{RedisDataType, RedisReplyStatus}
import cn.navigational.redisfx.model.RedisConnectInfo
import redis.clients.jedis.{Jedis, JedisPool}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

class JedisUtil(private val jedisPool: JedisPool, val connectInfo: RedisConnectInfo) {
  def listDbCount(): Future[Int] = Future {
    val jedis = jedisPool.getResource
    try {
      val list = jedis.configGet("databases")
      list.get(1).toInt
    }
    finally if (jedis != null) jedis.close()
  }

  def lsAllKey(database: Int): Future[Array[String]] = {
    this.executeCommand[Array[String]](jedis => jedis.keys("*").asScala.toArray, database)
  }

  def get(key: String, database: Int): Future[String] = {
    this.executeCommand[String](jedis => jedis.get(key), database)
  }

  def hGet(key: String, database: Int): Future[String] = {
    this.executeCommand[String](jedis => JSONUtil.objToJson(jedis.hgetAll(key)), database)
  }

  def setEx(key: String, value: String, database: Int, ttl: Int = -1): Future[Boolean] = {
    this.executeCommand[Boolean](jedis => {
      val status = if (ttl < 0) {
        jedis.set(key, value)
      } else {
        jedis.setex(key, ttl, value)
      }
      status.equals(RedisReplyStatus.OK.toString)
    }, database)
  }

  def hSet(key: String, field: String, value: String, database: Int): Future[Boolean] = {
    this.executeCommand[Boolean](jedis => jedis.hset(key, field, value) > 0, database)
  }

  def hSet(key: String, attr: java.util.Map[String, String], database: Int): Future[Boolean] = {
    this.executeCommand[Boolean](jedis => jedis.hset(key, attr) > 0, database)
  }

  def del(key: String, database: Int): Future[Long] = {
    this.executeCommand[Long](jedis => jedis.del(key), database)
  }

  def ttl(key: String, database: Int): Future[Long] = {
    this.executeCommand[Long](jedis => jedis.ttl(key), database)
  }

  def setTtl(key: String, ttl: Int, database: Int): Future[Long] = {
    this.executeCommand[Long](jedis => jedis.expire(key, ttl), database)
  }

  def rename(oldKey: String, newKey: String, database: Int): Unit = {
    this.executeCommand[String](jedis => jedis.rename(oldKey, newKey), database)
  }

  def typeKey(key: String, database: Int): Future[RedisDataType] = {
    this.executeCommand[RedisDataType](jedis => RedisDataType.getDataType(jedis.`type`(key)), database)
  }

  /**
   * 销毁Jedis连接池
   */
  def destroy(): Unit = {
    if (this.jedisPool.isClosed) {
      return
    }
    this.jedisPool.close()
  }

  /**
   * 使用高阶函数封装通用执行redis命令函数,
   *
   * @tparam T 执行完命令，返回数据类型
   * @return
   */
  private def executeCommand[T](executor: Jedis => T, database: Int): Future[T] = Future {
    val jedis = this.jedisPool.getResource
    try {
      jedis.select(database)
      executor.apply(jedis)
    } finally if (jedis != null) jedis.close()
  }
}