<?xml version="1.0" encoding="UTF-8"?>
<!--
  下传 generate.xsl 示例
  作用: 将 FMSY 标准中间 XML 转换为目标系统要求的格式
  XmlConverter 先生成 <data><record>...</record></data>,再经此 XSL 转换
-->
<xsl:stylesheet version="1.0"
  xmlns:xsl="http://www.w3.org/1999/XSL/Transform">

  <xsl:output indent="yes"/>

  <xsl:template match="/">
    <Orders>
      <xsl:apply-templates select="data/record"/>
    </Orders>
  </xsl:template>

  <xsl:template match="record">
    <Order>
      <Header>
        <OrderNo><xsl:value-of select="ORDER_ID"/></OrderNo>
        <Date><xsl:value-of select="ORDER_DATE"/></Date>
      </Header>
      <Customer>
        <Info>
          <Name><xsl:value-of select="CUSTOMER_NAME"/></Name>
        </Info>
      </Customer>
      <Payment>
        <Amount><xsl:value-of select="AMOUNT"/></Amount>
      </Payment>
    </Order>
  </xsl:template>

</xsl:stylesheet>
