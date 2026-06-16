<?xml version="1.0" encoding="UTF-8"?>
<!--
  上传 parse.xsl 示例
  作用: 将外部系统的复杂 XML 转换为 FMSY 标准中间格式
  标准中间格式: <data><record><field>value</field>...</record></data>
  转换后由 XmlConverter 的 StAX 迭代器按 recordElement="record" 统一解析
-->
<xsl:stylesheet version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
  xmlns:ns="http://example.com/source-namespace">

  <!-- 不认识的命名空间里的元素直接忽略 -->
  <xsl:template match="ns:*" mode="strip">
    <xsl:element name="{local-name()}">
      <xsl:apply-templates mode="strip"/>
    </xsl:element>
  </xsl:template>

  <xsl:template match="/">
    <data>
      <xsl:apply-templates select="//Order"/>
    </data>
  </xsl:template>

  <xsl:template match="Order">
    <record>
      <ORDER_ID><xsl:value-of select="OrderHeader/OrderNo"/></ORDER_ID>
      <CUSTOMER_NAME><xsl:value-of select="Party/Name"/></CUSTOMER_NAME>
      <AMOUNT><xsl:value-of select="Totals/Amount"/></AMOUNT>
      <ORDER_DATE><xsl:value-of select="OrderHeader/Date"/></ORDER_DATE>
    </record>
  </xsl:template>

</xsl:stylesheet>
